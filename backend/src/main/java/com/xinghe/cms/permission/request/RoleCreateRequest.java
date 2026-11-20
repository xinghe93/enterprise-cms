package com.xinghe.cms.permission.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.xinghe.cms.common.json.StrictStringDeserializer;
import com.xinghe.cms.permission.validation.ContractNotBlank;
import tools.jackson.databind.annotation.JsonDeserialize;

/**
 * 创建角色请求体（OpenAPI {@code RoleCreate} schema）。
 *
 * <p>字段均为必填且至少包含一个非空白字符（OpenAPI {@code minLength: 1} +
 * {@code pattern: \S}，由 {@link ContractNotBlank} 表达）。该注解按契约的
 * ECMAScript {@code \s} 判定空白，与服务层去首尾空白/判空共用
 * {@link com.xinghe.cms.permission.support.ContractWhitespace} 同一真源——
 * 不用 {@code @NotBlank}（Java {@code isBlank()} 会把 U+001C 误判为空白、
 * 又认不出 NBSP，在 {@code @Valid} 阶段就会错拒契约允许的名称）。长度上限
 * （64/100）不在 Bean Validation 中校验——{@code @Size} 按 UTF-16 代码单元
 * 统计，会把增补字符（如 emoji）重复计数，与 OpenAPI maxLength 的"字符数"
 * 语义不符；由服务层按 Unicode 码点校验。</p>
 *
 * <p>未知字段由 Jackson 全局严格模式（{@code fail-on-unknown-properties: true}）
 * 拦截为 400；字段类型错误（如数字形式的 roleCode）经
 * {@link StrictStringDeserializer} 拒绝为 400。</p>
 *
 * @param roleCode 角色编码，唯一、创建后不可改
 * @param roleName 角色名称
 */
public record RoleCreateRequest(
        @ContractNotBlank(message = "角色编码不能为空白")
        @JsonDeserialize(using = StrictStringDeserializer.class)
        String roleCode,
        @ContractNotBlank(message = "角色名称不能为空白")
        @JsonDeserialize(using = StrictStringDeserializer.class)
        String roleName) {
}
