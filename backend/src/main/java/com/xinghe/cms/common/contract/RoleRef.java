package com.xinghe.cms.common.contract;

/**
 * 角色引用：跨模块传递角色时的最小视图，只含 ID、编码与名称，不含状态与时间戳。
 *
 * <p>由 B 模块的 {@code RoleDirectoryService} 返回（如 A 的用户角色替换回显场景）。
 * 注意：本类型不用于鉴权判断，鉴权一律使用 {@link AuthorizationSnapshot}。</p>
 *
 * @param id       角色 ID，非 null
 * @param roleCode 角色编码，非 null
 * @param roleName 角色名称，非 null
 */
public record RoleRef(Long id, String roleCode, String roleName) {

    public RoleRef {
        java.util.Objects.requireNonNull(id, "id 不能为 null");
        java.util.Objects.requireNonNull(roleCode, "roleCode 不能为 null");
        java.util.Objects.requireNonNull(roleName, "roleName 不能为 null");
    }
}
