package com.xinghe.cms.user.service;

import com.xinghe.cms.common.exception.BusinessException;
import com.xinghe.cms.common.response.ResultCode;
import com.xinghe.cms.security.AuthSessionService;
import com.xinghe.cms.user.mapper.UserAdminMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 员工启停（{@code PUT /api/admin/users/{id}/status}），会话撤销的唯一业务入口。
 *
 * <p>契约《接口与协作约定》3.1 / 7.2：停用使该账号所有旧会话失效，重新启用也不恢复旧
 * Token；重复设置同一状态返回 200 且不重复写入；<strong>数据库事务提交之后</strong>
 * 才能刷新会话失效状态，回滚时不得先清 Redis（否则登录的「复查状态 → 比对纪元 → 签发」
 * 时序会被破坏，可能签出在重新启用后仍可用的 Token）。</p>
 *
 * <p>这里刻意使用编程式 {@link TransactionTemplate} 而不是
 * {@code @Transactional} + {@code TransactionSynchronization#afterCommit()}：
 * Spring 会捕获并只记录日志地吞掉 {@code afterCommit} 抛出的异常，Redis 撤销失败时
 * 接口仍会返回 200，等于把「状态已停用但旧 Token 仍可用」伪装成成功。
 * {@code TransactionTemplate.execute} 返回时事务已提交或已回滚，紧接着调用
 * {@link AuthSessionService#revokeAllForUser(Long)}，撤销失败会正常向上抛成 500，
 * 不会伪装成功；同时 {@code BearerTokenAuthenticationFilter} 发现停用时的条件撤销
 * 仍是兜底，旧 Token 再次访问会被拒绝并轮换纪元。</p>
 */
@Service
public class UserStatusService {

    private final TransactionTemplate transactions;
    private final UserAdminMapper userMapper;
    private final AuthSessionService sessions;

    public UserStatusService(PlatformTransactionManager transactionManager,
            UserAdminMapper userMapper, AuthSessionService sessions) {
        this.transactions = new TransactionTemplate(transactionManager);
        this.userMapper = userMapper;
        this.sessions = sessions;
    }

    /**
     * @param userId 目标员工 ID（已由 Controller 校验为十进制正整数字符串）
     * @param status 0 停用 / 1 启用（已由 DTO 严格校验）
     * @throws BusinessException 用户不存在 404；并发下状态写入失败 409
     */
    public void changeStatus(long userId, int status) {
        Boolean changed = transactions.execute(transactionStatus -> {
            Integer current = userMapper.findStatusForUpdate(userId);
            if (current == null) {
                throw new BusinessException(ResultCode.NOT_FOUND, "员工不存在");
            }
            if (current == status) {
                // 契约 249：重复设置同一状态返回 200，不写库、不插关联、也不撤销会话。
                return Boolean.FALSE;
            }
            int rows = userMapper.updateStatus(userId, status);
            if (rows != 1) {
                // 行锁已持有，正常不会发生；区分于「不存在」，按业务冲突处理而不是静默成功。
                throw new BusinessException(ResultCode.CONFLICT, "员工状态已变更，请重试");
            }
            return Boolean.TRUE;
        });

        // 事务已提交（回调抛异常时这里不会执行，回滚不触碰 Redis）。
        if (Boolean.TRUE.equals(changed)) {
            sessions.revokeAllForUser(userId);
        }
    }
}
