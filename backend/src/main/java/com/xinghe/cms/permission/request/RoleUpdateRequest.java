package com.xinghe.cms.permission.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.xinghe.cms.common.json.StrictStringDeserializer;
import com.xinghe.cms.permission.validation.ContractNotBlank;
import tools.jackson.databind.annotation.JsonDeserialize;

/**
 * 修改角色请求体（OpenAPI {@code RoleUpdate} schema，{@code PUT /api/admin/roles/{id}}）。
 *
 * <p>契约：修改角色只改 {@code roleName}，不改变编码、状态与关联；请求体仅此一个
 * 字段（{@code additionalProperties: false}，未知字段由 Jackson 全局严格模式拦截）。</p>
 *
 * <p>字段必填且至少包含一个非空白字符（OpenAPI {@code minLength: 1} +
 * {@code pattern: \S}，由 {@link ContractNotBlank} 表达）。该注解按契约的
 * ECMAScript {@code \s} 判定空白，与服务层去首尾空白/判空共用
 * {@link com.xinghe.cms.permission.support.ContractWhitespace} 同一真源——
 * 不用 {@code @NotBlank}（Java {@code isBlank()} 会把 U+001C 误判为空白、
 * 又认不出 NBSP，在 {@code @Valid} 阶段就会错拒契约允许的名称）。长度上限
 * （100）不在 Bean Validation 中校验——{@code @Size} 按 UTF-16 代码单元统计，
 * 与 OpenAPI maxLength 的"字符数"语义不符；由服务层按 Unicode 码点校验，
 * 且先于去空白。</p>
 *
 * <p>字段类型错误（如数字形式的 roleName）经 {@link StrictStringDeserializer} 拒绝为 400。</p>
 *
 * @param roleName 新的角色名称
 */
public record RoleUpdateRequest(
        @ContractNotBlank(message = "角色名称不能为空白")
        @JsonDeserialize(using = StrictStringDeserializer.class)
        String roleName) {
}
