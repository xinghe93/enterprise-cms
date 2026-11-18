package com.xinghe.cms.permission.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.xinghe.cms.common.json.StrictStringDeserializer;
import jakarta.validation.constraints.NotBlank;
import tools.jackson.databind.annotation.JsonDeserialize;

/**
 * 修改角色请求体（OpenAPI {@code RoleUpdate} schema，{@code PUT /api/admin/roles/{id}}）。
 *
 * <p>契约：修改角色只改 {@code roleName}，不改变编码、状态与关联；请求体仅此一个
 * 字段（{@code additionalProperties: false}，未知字段由 Jackson 全局严格模式拦截）。</p>
 *
 * <p>字段必填且至少包含一个非空白字符（OpenAPI {@code minLength: 1} +
 * {@code pattern: \S}，由 {@code @NotBlank} 表达）。长度上限（100）不在
 * Bean Validation 中校验——{@code @Size} 按 UTF-16 代码单元统计，与 OpenAPI
 * maxLength 的"字符数"语义不符；由服务层按 Unicode 码点校验，且先于去空白。</p>
 *
 * <p>字段类型错误（如数字形式的 roleName）经 {@link StrictStringDeserializer} 拒绝为 400。</p>
 *
 * @param roleName 新的角色名称
 */
public record RoleUpdateRequest(
        @NotBlank(message = "角色名称不能为空白")
        @JsonDeserialize(using = StrictStringDeserializer.class)
        String roleName) {
}
