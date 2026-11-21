package com.xinghe.cms.user.service;

import com.xinghe.cms.log.annotation.AuditLog;
import com.xinghe.cms.user.vo.DepartmentVO;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * 部门写操作的成功审计步骤（A 模块对接 D 的 {@link AuditLog}）。
 *
 * <p>《跨模块服务接口》第 5 节要求「成功日志随外层事务提交，失败日志在回滚后记录」。
 * {@code AuditLogAspect} 只有在进入时事务已开启才会注册事务同步器，把 SUCCESS 写入
 * 业务事务（随业务一起提交/回滚，写入失败则业务一起回滚），并把 FAILURE 用独立事务
 * 补记。因此 4 个写操作的 {@code @AuditLog} 不能标在 Controller（切面先于事务进入，
 * 成功日志会在业务提交后单独写入且写入异常被吞——PR #40 审查 P2），也不能直接标在
 * 用 {@code TransactionTemplate} 开事务的 Service 入口上。</p>
 *
 * <p>正确接线与 {@code AuditLogTransactionProxyTests}（D 交付的真实代理回归）一致：
 * Controller → Service（{@code TransactionTemplate} 开启事务）→ <strong>事务内经 Spring
 * 代理调用本 Bean 的审计方法</strong>，业务动作以 {@link Supplier}/{@link Runnable} 传入、
 * 在审计方法内部执行。只读的部门树查询不审计。</p>
 */
@Component
public class DepartmentAuditedActions {

    /** 创建部门。 */
    @AuditLog(operation = "department.create")
    public DepartmentVO create(Supplier<DepartmentVO> action) {
        return action.get();
    }

    /** 修改部门（完整 PUT）。 */
    @AuditLog(operation = "department.update")
    public DepartmentVO update(Supplier<DepartmentVO> action) {
        return action.get();
    }

    /** 删除空部门。 */
    @AuditLog(operation = "department.delete")
    public void delete(Runnable action) {
        action.run();
    }

    /** 启用/停用部门。 */
    @AuditLog(operation = "department.status")
    public void changeStatus(Runnable action) {
        action.run();
    }
}
