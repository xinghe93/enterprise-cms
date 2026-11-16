package com.xinghe.cms.user.vo;

/** 已分配角色摘要（OpenAPI: RoleBrief）；含停用项，仅供管理回显，不用于鉴权。 */
public record RoleBriefVO(String id, String roleCode, String roleName, Integer status) {
}
