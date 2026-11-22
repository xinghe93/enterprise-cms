package com.xinghe.cms.permission.service.impl;

import com.xinghe.cms.log.annotation.AuditLog;
import org.springframework.stereotype.Component;

/**
 * 权限项写操作的审计接线（与 {@link RoleWriteAuditMarker} 同源同因）。
 *
 * <p>启停接口存在"重复同状态"的幂等无变更路径（契约：重复设置同一状态返回
 * 200，不重复写库）。这些路径<b>没有发生状态变更</b>，如果审计切面包住整个
 * 服务方法，切面会在提交前写入 SUCCESS——一旦这条审计写入失败，本应 200 的
 * 幂等请求反而失败。因此启停的审计挂在本类方法上，服务层仅在<b>确实发生
 * 变更</b>（排他锁下读到的状态与目标不同）时才经 Spring 代理调用；幂等路径
 * 在调用之前就已返回，不产生任何审计记录。</p>
 *
 * <p><b>实际写入必须包进受审计代理</b>（2026-11-21 评审 P2 结论，B 模块写事务
 * 通用约定）：启停的状态更新以 {@link Runnable} 传入
 * {@link #permissionStatusChanged(Runnable)}，在代理方法内部执行——切面在
 * 进入时先注册事务同步器、再执行写入。UPDATE 抛异常时事务回滚，切面仍用
 * 独立新事务补记 {@code permission.status / FAILURE}（《跨模块服务接口
 * V1.0》第 120、126 行），不会出现"业务回滚但失败审计缺失"。</p>
 *
 * <p>创建与修改必然产生状态变更（无幂等跳过路径），{@code @AuditLog} 直接
 * 标注在 {@code PermissionAdminServiceImpl} 的方法上——整个方法体在受审计
 * 代理内执行，同样满足"写库前注册回调"，不经过本类。</p>
 */
@Component
public class PermissionWriteAuditMarker {

    /**
     * 权限项状态变更的审计代理：调用即表示本次事务中权限项状态即将实际变更
     * （调用前已完成幂等判断与 404 校验）。<b>实际状态更新以 {@code writeAction}
     * 传入、在本方法（受 {@code @AuditLog} 环绕的代理）内部执行</b>：
     * <ul>
     *   <li>写入正常返回：{@code beforeCommit} 随业务事务写
     *       {@code permission.status / SUCCESS}；</li>
     *   <li>写入抛异常：异常经代理冒泡，业务事务回滚，
     *       {@code afterCompletion} 用独立新事务补记
     *       {@code permission.status / FAILURE}，不吞异常。</li>
     * </ul>
     *
     * @param writeAction 状态更新动作，在受审计代理内部执行
     */
    @AuditLog(operation = "permission.status")
    public void permissionStatusChanged(Runnable writeAction) {
        writeAction.run();
    }
}
