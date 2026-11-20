package com.xinghe.cms.user.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import tools.jackson.databind.annotation.JsonDeserialize;

/**
 * 修改员工请求（OpenAPI: UserUpdate）。
 *
 * <p>完整 PUT：realName 与 departmentId 都必须出现；departmentId 为 null 表示清除部门。
 * 不允许携带 username / password / status / roleIds 等字段。</p>
 */
@JsonIgnoreProperties(ignoreUnknown = false)
@JsonDeserialize(using = UserUpdateRequestDeserializer.class)
public record UserUpdateRequest(String realName, Long departmentId) {
}
