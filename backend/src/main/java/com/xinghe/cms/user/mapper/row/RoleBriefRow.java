package com.xinghe.cms.user.mapper.row;

/** 用户 — 用户角色 — 角色 JOIN 的结果行；含停用项，仅用于管理回显。 */
public record RoleBriefRow(Long id, String roleCode, String roleName, Integer status) {
}
