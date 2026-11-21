package com.xinghe.cms.user.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import tools.jackson.databind.annotation.JsonDeserialize;

/**
 * 创建部门请求（OpenAPI: DepartmentCreate）。
 *
 * <p>deptCode / deptName 必填；parentId 可省略或为 null（根）；sortOrder 可省略（默认 0）。
 * 默认 status=1，不接受客户端传入 status / deptCode 变更字段。</p>
 */
@JsonIgnoreProperties(ignoreUnknown = false)
@JsonDeserialize(using = DepartmentCreateRequestDeserializer.class)
public record DepartmentCreateRequest(
        Long parentId, String deptCode, String deptName, int sortOrder) {
}
