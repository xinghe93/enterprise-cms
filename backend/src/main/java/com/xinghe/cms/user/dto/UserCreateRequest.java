package com.xinghe.cms.user.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import tools.jackson.databind.annotation.JsonDeserialize;

/**
 * 创建员工请求（OpenAPI: UserCreate）。
 *
 * <p>username / password / realName 必填；departmentId 可省略或为 null（无部门）；
 * 不接受角色字段，角色必须通过 {@code PUT /api/admin/users/{id}/roles} 单独分配。
 * 字段类型、长度、未知字段、ID 格式由 {@link UserCreateRequestDeserializer} 严格校验。</p>
 */
@JsonIgnoreProperties(ignoreUnknown = false)
@JsonDeserialize(using = UserCreateRequestDeserializer.class)
public record UserCreateRequest(
        String username, String password, String realName, Long departmentId) {
}
