package com.xinghe.cms.user.service;

import com.xinghe.cms.log.annotation.AuditLog;
import com.xinghe.cms.user.vo.UserVO;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * 员工写操作的成功审计步骤（A 模块对接 D 的 {@link AuditLog}）。
 *
 * <p>《跨模块服务接口》第 5 节要求「成功日志随外层事务提交，失败日志在回滚后记录」。
 * {@code AuditLogAspect} 只有在进入时事务已开启才会注册事务同步器，把 SUCCESS 写入
 * 业务事务（随业务一起提交/回滚，写入失败则业务一起回滚），并把 FAILURE 用独立新事务
 * 补记。因此四个写操作的 {@code @AuditLog} 不能标在 Controller（事务尚未开启，成功日志
 * 会在业务提交后单独写入，写入失败时业务仍成功——PR #39 审查 P2），也不能直接标在
 * 用 {@code TransactionTemplate} 开事务的 Service 入口方法上（切面先于事务进入，
 * 同样走「无事务」分支）。</p>
 *
 * <p>正确接线与 {@code AuditLogTransactionProxyTests}（D 交付的真实代理回归）一致：
 * Controller → Service（{@code TransactionTemplate} 开启事务）→ <strong>事务内经 Spring
 * 代理调用本 Bean 的审计方法</strong>，业务动作以 {@link Supplier}/{@link Runnable} 传入、
 * 在审计方法内部执行。每个 HTTP 写入口只标记一次；只读查询不审计。</p>
 */
@Component
public class UserAuditedActions {

    /** 创建员工：业务动作（含部门链校验、密码哈希、落库与回读）在审计方法内执行。 */
    @AuditLog(operation = "user.create")
    public UserVO createUser(Supplier<UserVO> action) {
        return action.get();
    }

    /** 修改员工（完整 PUT）。 */
    @AuditLog(operation = "user.update")
    public UserVO updateUser(Supplier<UserVO> action) {
        return action.get();
    }

    /** 全量替换角色。 */
    @AuditLog(operation = "user.assign-role")
    public void replaceRoles(Runnable action) {
        action.run();
    }

    /** 启用/停用员工（含启用方向的提交前撤销）。 */
    @AuditLog(operation = "user.status")
    public UserStatusService.StatusTransition changeStatus(
            Supplier<UserStatusService.StatusTransition> action) {
        return action.get();
    }
}
