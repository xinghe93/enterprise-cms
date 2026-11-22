package com.xinghe.cms.user.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import tools.jackson.databind.annotation.JsonDeserialize;

/**
 * 修改部门请求（OpenAPI: DepartmentUpdate）。
 *
 * <p>完整 PUT：parentId（可 null）、deptName、sortOrder 都必填；不修改 deptCode / status。</p>
 */
@JsonIgnoreProperties(ignoreUnknown = false)
@JsonDeserialize(using = DepartmentUpdateRequestDeserializer.class)
public record DepartmentUpdateRequest(Long parentId, String deptName, int sortOrder) {
}
