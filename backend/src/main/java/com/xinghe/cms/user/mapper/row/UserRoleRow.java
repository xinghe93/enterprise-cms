package com.xinghe.cms.user.mapper.row;

/** 批量回显员工角色时的一行；SQL 已按 userId、roleId 升序。 */
public record UserRoleRow(
        Long userId, Long roleId, String roleCode, String roleName, Integer status) {
}
