package com.xinghe.cms.user.service;

import com.xinghe.cms.common.exception.BusinessException;
import com.xinghe.cms.common.response.ResultCode;
import com.xinghe.cms.security.AuthSessionService;
import com.xinghe.cms.user.mapper.UserAdminMapper;
import com.xinghe.cms.user.mapper.row.UserStatusSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 员工启停（{@code PUT /api/admin/users/{id}/status}），会话撤销的唯一业务入口。
 *
 * <p>契约《接口与协作约定》3.1 / 7.2：停用使该账号所有旧会话失效，重新启用也不恢复旧
 * Token；重复设置同一状态返回 200 且不重复写入；依赖故障不能伪装成登录成功。</p>
 *
 * <p><strong>撤销版本取独立的状态版本</strong>：{@code sys_user.status_version}
 * （004 迁移脚本）仅在状态真正变化（0↔1）时由 {@code updateStatus} 原子 +1，
 * 与资料更新无关，也不依赖 updated_at 的毫秒精度（同一毫秒内两次状态变更无法取得
 * 独立递增的版本——PR #39 审查 P1/P2）。旧实现把资料更新也会抬高的 updated_at 当撤销
 * 版本，会导致重复启用/改名后版本高于已撤销记录而误踢正常会话；新版本号下，同状态
 * 请求的重复撤销一律被 Lua 幂等跳过，只有真正待补偿的撤销（此前失败）才会执行。</p>
 *
 * <p><strong>两个方向的撤销时序刻意不对称</strong>（PR #39 审查 P1：确保重新启用前
 * 旧会话已失效）：</p>
 * <ul>
 * <li><strong>停用（1→0）</strong>：数据库事务提交<strong>之后</strong>才轮换纪元——这是契约
 *   的「提交点是干净切分」要求：提交前轮换会让并发登录在轮换后、提交前签出仍可用的
 *   Token。撤销失败返回 500，但状态已停用，{@code BearerTokenAuthenticationFilter}
 *   会拒绝并兜底撤销旧 Token；重试（同状态）按版本补偿，不再被「状态相同」短路。</li>
 * <li><strong>重新启用（0→1）</strong>：在事务内、提交<strong>之前</strong>完成撤销——
 *   前序停用的撤销失败时在此同步补偿，撤销失败则整体回滚、返回 500，绝不出现
 *   「数据库已启用但旧会话仍有效」的复活窗口。停用期间不可能产生新登录，提交前
 *   撤掉的只会是停用前的旧会话；回滚时账号仍停用，多轮换一次纪元无副作用。</li>
 * </ul>
 *
 * <p>这里刻意使用编程式 {@link TransactionTemplate} 而不是
 * {@code @Transactional} + {@code TransactionSynchronization#afterCommit()}：
 * Spring 会捕获并只记录日志地吞掉 {@code afterCommit} 抛出的异常，Redis 撤销失败时
 * 接口仍会返回 200，等于把「状态已停用但旧 Token 仍可用」伪装成成功。
 * {@code TransactionTemplate.execute} 返回时事务已提交或已回滚。</p>
 *
 * <p><strong>成功审计随业务事务提交</strong>：本方法内的业务动作经
 * {@link UserAuditedActions#changeStatus(Supplier)} 在事务内执行（PR #39 审查 P2）。
 * 注意停用方向的提交后撤销失败仍会向调用方抛 500，但数据库状态变更确已提交，
 * 审计在事务提交点记 SUCCESS 是既定语义；启用方向的撤销失败发生在审计方法内部，
 * 事务回滚、按约定补记 FAILURE。</p>
 */
@Service
public class UserStatusService {

    private static final Logger log = LoggerFactory.getLogger(UserStatusService.class);

    /** 一次启停的结果：撤销用的版本号，以及是否为真正的 0→1 启用迁移。 */
    record StatusTransition(long version, boolean enabling) {
    }

    private final TransactionTemplate transactions;
    private final UserAdminMapper userMapper;
    private final AuthSessionService sessions;
    private final UserAuditedActions auditedActions;
    private final UserWriteLocks userWriteLocks;

    public UserStatusService(PlatformTransactionManager transactionManager,
            UserAdminMapper userMapper, AuthSessionService sessions,
            UserAuditedActions auditedActions, UserWriteLocks userWriteLocks) {
        this.transactions = new TransactionTemplate(transactionManager);
        this.userMapper = userMapper;
        this.sessions = sessions;
        this.auditedActions = auditedActions;
        this.userWriteLocks = userWriteLocks;
    }

    /**
     * @param userId 目标员工 ID（已由 Controller 校验为十进制正整数字符串）
     * @param status 0 停用 / 1 启用（已由 DTO 严格校验）
     * @throws BusinessException 用户不存在 404；并发下状态写入失败 409
     */
    public void changeStatus(long userId, int status) {
        StatusTransition transition = transactions.execute(transactionStatus ->
                auditedActions.changeStatus(() -> doChangeStatus(userId, status)));

        // 启用方向已在事务内（提交前）完成撤销；停用方向与同状态补偿在提交后执行。
        // 幂等撤销：仅当版本高于 Redis 已记录版本才轮换纪元；撤销失败会向上抛成 500
        // 且不抬高记录，重试可补偿；同状态重复请求因 status_version 未变化而被跳过，
        // 资料更新也不抬升该版本，绝不会误踢启用账号的正常会话。
        if (!transition.enabling()) {
            try {
                sessions.revokeAllForUserUpToVersion(userId, transition.version());
            } catch (RuntimeException exception) {
                // 数据库状态已经提交，不能回滚也不能伪装成功：按契约向上抛成 500。
                // 这里用明确日志标出「部分成功」及补偿方式：版本记录未抬高，同状态重试
                // 会再次调用带版本撤销；期间任何旧请求都会被认证过滤器按停用状态拒绝并兜底撤销。
                log.error("员工 {} 状态已提交但会话撤销失败（版本 {}），请重试同状态接口补偿；"
                        + "在此之前旧会话由认证过滤器拒绝", userId, transition.version(), exception);
                throw exception;
            }
        }
    }

    /**
     * 事务内执行（审计方法包裹）：行锁读快照 → 校验 → 写状态并递增版本 → 回读版本；
     * 启用方向在返回前（提交前）按新版本完成撤销。
     */
    private StatusTransition doChangeStatus(long userId, int status) {
        UserStatusSnapshot current = userWriteLocks.lockTargetAndOperator(userId,
                () -> userMapper.findStatusForUpdate(userId));
        if (current == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "员工不存在");
        }
        if (current.status() == status) {
            // 契约：重复设置同一状态返回 200，不写库。版本取当前 status_version，
            // 交给带版本的幂等撤销：仅当此前撤销失败（记录未抬高）才补偿执行。
            return new StatusTransition(current.statusVersion(), false);
        }
        int rows = userMapper.updateStatus(userId, status);
        if (rows != 1) {
            // 行锁已持有，正常不会发生；区分于「不存在」，按业务冲突处理而不是静默成功。
            throw new BusinessException(ResultCode.CONFLICT, "员工状态已变更，请重试");
        }
        UserStatusSnapshot updated = userMapper.findStatusVersion(userId);
        if (updated == null) {
            throw new BusinessException(ResultCode.CONFLICT, "员工状态已变更，请重试");
        }
        if (status == 1) {
            // 重新启用必须在提交前确认旧会话已失效：前序停用撤销失败时在此同步补偿，
            // 撤销失败抛异常 → 本事务回滚 → 500，不产生「已启用但旧会话仍有效」的窗口。
            sessions.revokeAllForUserUpToVersion(userId, updated.statusVersion());
        }
        return new StatusTransition(updated.statusVersion(), status == 1);
    }
}
