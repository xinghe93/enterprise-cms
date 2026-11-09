package com.xinghe.cms.permission.service;

import com.xinghe.cms.common.contract.RoleRef;
import java.util.List;

/**
 * 角色目录服务（B 模块对外契约，见《跨模块服务接口 V1.0》第 2 节）。
 *
 * <p>供 A 的用户角色替换流程调用：先在事务中校验目标用户，再校验并锁定角色，
 * 最后全量替换 {@code sys_user_role}。B 不替 A 写关联表。</p>
 */
public interface RoleDirectoryService {

    /**
     * 校验并共享锁定待分配角色。
     *
     * <p>语义（与文档逐条对应）：</p>
     * <ul>
     *   <li>空列表（或 null）返回空列表，不发出 IN 查询；</li>
     *   <li>同一 ID 重复只校验一次，结果按 ID 升序；</li>
     *   <li>任一 ID 不存在 → {@code NOT_FOUND}（404）；</li>
     *   <li>任一角色停用 → {@code CONFLICT}（409）；</li>
     *   <li>角色行加共享锁（{@code FOR SHARE}）直到外层事务结束。</li>
     * </ul>
     *
     * @param roleIds 目标角色 ID 列表，允许重复、允许空
     * @return 按 ID 升序的角色引用列表，永不为 null
     * @throws com.xinghe.cms.common.exception.BusinessException 404 角色不存在、409 角色停用
     * @throws org.springframework.transaction.IllegalTransactionStateException
     *         调用方未开启事务时（本方法要求 {@code MANDATORY}）
     */
    List<RoleRef> lockAssignableRoles(List<Long> roleIds);
}
