package com.xinghe.cms.permission.service.impl;

import com.xinghe.cms.log.aspect.AuditLogAspect;
import com.xinghe.cms.log.annotation.AuditLog;
import org.springframework.stereotype.Component;

/**
 * 角色写操作的审计接线（评审 P2）。
 *
 * <p>背景：启停接口与角色权限全量替换的契约都要求"重复设置同一状态 / 全量替换
 * 相同有效集合均按幂等返回 200，不重复写库"（协作约定 8.1）。这些路径
 * <b>没有发生状态变更</b>，如果审计切面仍然包住整个服务方法，切面会在提交前
 * 写入 SUCCESS——一旦这条审计写入失败，本应 200 的幂等请求反而失败。因此审计
 * 只能挂在"确实发生变更"的路径上，而 {@link AuditLogAspect} 没有条件跳过
 * 机制，{@code @AuditLog} 直接标注在服务方法上无法区分有无写入。</p>
 *
 * <p>做法：服务方法先完成校验（400/404/409）与幂等集合比较，只在
 * <b>确实发生变更</b>时才经 Spring 代理调用本类方法——切面在<b>进入时</b>
 * 注册事务同步器，{@code beforeCommit} 阶段随业务事务一起写 SUCCESS；审计写入
 * 失败则整个事务回滚（此时确实变了，回滚 + 报错符合"绝不出现业务生效但审计
 * 缺失"）。无状态变更路径在调用本类方法之前就已返回，不会产生任何审计记录。</p>
 *
 * <p><b>差集写入必须包在受审计代理内（本轮评审 P2）</b>：
 * {@link RolePermissionServiceImpl#replacePermissions} 的删旧 / 插新以
 * {@link Runnable} 传入 {@link #rolePermissionsChanged(Runnable)}，在代理方法
 * 内部执行。这样切面在"写库之前"就已注册好 {@code afterCompletion} 回调：
 * 删除成功、插入抛异常时，数据库事务回滚，切面仍能用独立新事务补记
 * {@code role.assign-permission / FAILURE}（《跨模块服务接口 V1.0》第 120、
 * 126 行"需要失败审计时在业务事务回滚后另行写 FAILURE / 失败日志在回滚后
 * 记录"）。旧实现把差集写入放在空锚点调用<b>之前</b>、写入全部返回后才调用
 * 锚点，Mapper 抛错时切面根本没进入、同步器没注册，回滚后漏记失败审计。
 * 切面 {@code durationMs} 对本方法统计的是差集写入耗时，不再是近似 0。</p>
 *
 * <p>启停接口 {@link #roleStatusChanged()} 仍是"确实变更"后的空锚点（本次评审
 * 范围仅为权限替换，保持既有语义不动）。创建与修改必然产生状态变更，
 * {@code @AuditLog} 仍直接标注在服务方法上，不经过本类。</p>
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

    /**
     * 角色权限集合变更的审计代理（评审 P2）：调用即表示本次事务中角色权限关联
     * 集合已发生变化（并非幂等的相同集合）。<b>实际差集写入以 {@code writeAction}
     * 传入，在本方法（受 {@code @AuditLog} 环绕的代理）内部执行</b>——切面在
     * 进入时先注册事务同步器，再执行写入，因此：
     * <ul>
     *   <li>写入正常返回：{@code beforeCommit} 随业务事务写
     *       {@code role.assign-permission / SUCCESS}；</li>
     *   <li>写入任一步抛异常：异常经代理冒泡到切面（{@code thrown=true} 后原样
     *       抛出），业务事务回滚，{@code afterCompletion} 用独立新事务补记
     *       {@code role.assign-permission / FAILURE}，不吞异常。</li>
     * </ul>
     *
     * <p>仅供 {@link RolePermissionServiceImpl#replacePermissions} 在校验通过、
     * 差集非空、经 Spring 代理调用；幂等（相同集合、差集均为空）路径在服务端
     * 早返、不调用本方法，不产生审计记录。404/409 校验失败也发生在本方法之前，
     * 切面未进入，不产生 FAILURE 审计（与 role.status 既有语义一致）。</p>
     *
     * @param writeAction 差集删除 / 插入动作，在受审计代理内部执行
     */
    @AuditLog(operation = "role.assign-permission")
    public void rolePermissionsChanged(Runnable writeAction) {
        writeAction.run();
    }
}
