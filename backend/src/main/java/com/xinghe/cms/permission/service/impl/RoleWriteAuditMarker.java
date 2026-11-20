package com.xinghe.cms.permission.service.impl;

import com.xinghe.cms.log.annotation.AuditLog;
import org.springframework.stereotype.Component;

/**
 * 角色写操作的审计标记方法（评审 P2）。
 *
 * <p>背景：启停接口的契约要求"重复设置同一状态、并发下条件更新未命中均按
 * 幂等返回 200，不重复写库"。这两条路径<b>没有发生状态变更</b>，如果审计切面
 * 仍然包住整个 {@code setStatus}，切面会在提交前写入 {@code role.status} /
 * SUCCESS——一旦这条审计写入失败，本应 200 的幂等请求反而失败。因此审计
 * 只能挂在"状态确实变更"的路径上，而 {@link AuditLogAspect} 没有条件跳过
 * 机制，{@code @AuditLog} 标注在 {@code setStatus} 上无法区分有无写入。</p>
 *
 * <p>做法：{@code setStatus} 先完成校验（400/404）与条件更新（评审 P2 的
 * {@code WHERE status<>?}），只在<b>影响行数大于 0（状态确实变更）</b>时才
 * 调用本类经 Spring 代理进入的标记方法——切面在进入时注册事务同步器，
 * {@code beforeCommit} 阶段随启停事务一起写 SUCCESS；审计写入失败则整个
 * 事务回滚（此时状态确实变了，回滚 + 报错符合"绝不出现业务生效但审计缺失"）。
 * 两条无状态变更路径在调用标记方法之前就已返回，不会产生任何审计记录。</p>
 *
 * <p>创建与修改必然产生状态变更，{@code @AuditLog} 仍直接标注在服务方法上，
 * 不经过本类。本类方法本身没有业务逻辑，耗时统计（切面 durationMs）对
 * status 操作近似为 0。</p>
 */
@Component
public class RoleWriteAuditMarker {

    /**
     * 启停状态变更的审计锚点：调用即表示本次事务中角色状态已发生变更，
     * 切面据此在事务提交时记录 role.status / SUCCESS。仅供
     * {@link RoleAdminServiceImpl#setStatus} 在条件更新命中后经代理调用。
     */
    @AuditLog(operation = "role.status")
    public void roleStatusChanged() {
        // 无业务逻辑：审计语义由切面实现（事务内注册同步器，beforeCommit 随事务提交）
    }
}
