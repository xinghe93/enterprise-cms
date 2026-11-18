package com.xinghe.cms.permission.service;

import com.xinghe.cms.permission.request.RoleCreateRequest;
import com.xinghe.cms.permission.request.RoleStatusRequest;
import com.xinghe.cms.permission.request.RoleUpdateRequest;
import com.xinghe.cms.permission.response.RoleView;

/**
 * 角色管理写服务（管理端）。当前批次：创建、修改、启停；
 * 角色已分配权限查询与全量替换在后续迭代补充。
 */
public interface RoleAdminService {

    /**
     * 创建角色。新角色默认启用（status=1）且未分配任何权限；
     * {@code roleCode} 全局唯一，重复时按契约返回 409。
     *
     * <p>时间字段由数据库默认值生成，插入后重新查询取回（契约：
     * "字段时间由数据库生成后重新查询，不能返回客户端伪造时间"）。</p>
     *
     * @param request 创建请求（已通过 MVC 层必填/类型校验）
     * @return 创建后的完整角色视图
     * @throws com.xinghe.cms.common.exception.BusinessException
     *         字段超长时 BAD_REQUEST；roleCode 重复时 CONFLICT（含并发唯一键冲突）
     */
    RoleView create(RoleCreateRequest request);

    /**
     * 修改角色（OpenAPI {@code PUT /api/admin/roles/{id}}）。契约：只改 roleName，
     * 不改变编码、状态与关联；不存在返回 404；返回修改后的完整 Role。
     *
     * <p>字段规范化同创建：先按码点校验原始长度，再去空白并检查非空。</p>
     *
     * @param id      角色 ID（已通过 {@code Ids.parse} 校验为合法十进制）
     * @param request 修改请求
     * @return 修改后的完整角色视图（updatedAt 为数据库重新查询值）
     * @throws com.xinghe.cms.common.exception.BusinessException
     *         角色不存在时 NOT_FOUND；roleName 超长/空白时 BAD_REQUEST
     */
    RoleView update(Long id, RoleUpdateRequest request);

    /**
     * 启停角色（OpenAPI {@code PUT /api/admin/roles/{id}/status}）。契约：只改
     * 状态，不删除 user-role 或 role-permission 历史关联；重复设置同一状态
     * 幂等返回 200（不重复写库）；不存在返回 404。
     *
     * @param id      角色 ID
     * @param request 启停请求（目标状态 0/1）
     * @throws com.xinghe.cms.common.exception.BusinessException
     *         角色不存在时 NOT_FOUND；status 非 0/1 时 BAD_REQUEST
     */
    void setStatus(Long id, RoleStatusRequest request);
}
