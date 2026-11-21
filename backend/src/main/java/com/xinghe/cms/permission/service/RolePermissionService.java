package com.xinghe.cms.permission.service;

import com.xinghe.cms.permission.request.RolePermissionsUpdateRequest;
import com.xinghe.cms.permission.response.PermissionView;
import java.util.List;

/**
 * 角色权限关联服务（管理端）。
 *
 * <p>对齐 OpenAPI {@code GET /api/admin/roles/{id}/permissions}（已分配权限回显）
 * 与 {@code PUT /api/admin/roles/{id}/permissions}（全量替换）。读写职责内聚在
 * 本接口，不并入 {@link RoleAdminService}（角色主表的写操作）或
 * {@link RoleQueryService}（角色主表的读操作），避免改动已稳定的接口。</p>
 */
public interface RolePermissionService {

    /**
     * 查询角色已分配的全部权限（含停用项），按关联 ID（{@code permission_id}）升序，
     * 未分配返回空列表。仅用于管理回显，不能代替 {@code AuthorizationSnapshot} 鉴权。
     *
     * @param roleId 角色 ID（已由 {@code Ids.parse} 校验为合法十进制）
     * @return 权限视图列表，可能为空
     * @throws com.xinghe.cms.common.exception.BusinessException 角色不存在时 NOT_FOUND
     */
    List<PermissionView> getPermissions(long roleId);

    /**
     * 全量替换角色权限（OpenAPI {@code setRolePermissions}）。契约要点：
     * <ul>
     *   <li>空数组清空关联；0～100 项、去重；不存在权限 404、停用权限 409；</li>
     *   <li>单个事务内按"操作人用户 → 目标角色 → 待分配权限"顺序取锁（评审 P2，
     *       与 A 模块用户角色分配同序，避免审计外键锁形成死锁环），校验全部待分配
     *       权限后按差集替换关联（只删移除项、只插新增项，空表首次分配不发 DELETE）；
     *       任一失败整体回滚；</li>
     *   <li>既有停用权限也不能保留在本次替换请求中（即使集合未变也要校验，不能借幂等保留）；</li>
     *   <li>相同有效集合幂等返回 200（data=null），不重复插关联、不产生审计记录；</li>
     *   <li>提交后权限变更生效——首期每请求实时计算授权快照、无跨请求缓存可失效。</li>
     * </ul>
     *
     * <p>成功 data 由 Controller 统一包装为 null；本方法无返回值。</p>
     *
     * @param roleId  角色 ID（已由 {@code Ids.parse} 校验）
     * @param request 替换请求（DTO 层已校验 IdList：0～100、去重、合法 ID 字符串）
     * @throws com.xinghe.cms.common.exception.BusinessException
     *         角色不存在 / 权限不存在时 NOT_FOUND；权限已停用时 CONFLICT
     */
    void replacePermissions(long roleId, RolePermissionsUpdateRequest request);
}
