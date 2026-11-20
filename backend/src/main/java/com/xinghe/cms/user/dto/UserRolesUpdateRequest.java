package com.xinghe.cms.user.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import tools.jackson.databind.annotation.JsonDeserialize;

/**
 * 全量替换员工角色请求（OpenAPI: UserRolesUpdate）。
 *
 * <p>roleIds 为 0～100 个十进制 ID 字符串，不允许重复；空数组清空关联。
 * 停用角色不得出现在替换请求中，由 B 的 {@code RoleDirectoryService} 在事务内校验。</p>
 */
@JsonIgnoreProperties(ignoreUnknown = false)
@JsonDeserialize(using = UserRolesUpdateRequestDeserializer.class)
public record UserRolesUpdateRequest(List<Long> roleIds) {
}
