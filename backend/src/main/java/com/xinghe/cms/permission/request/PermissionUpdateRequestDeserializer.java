package com.xinghe.cms.permission.request;

import java.util.Set;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ValueDeserializer;

/**
 * {@link PermissionUpdateRequest} 的严格反序列化器（OpenAPI
 * {@code PermissionUpdate} schema，{@code PUT /api/admin/permissions/{id}}）。
 *
 * <p>契约：{@code required: [permissionName, path, method]} 且
 * {@code path}/{@code method} 是 <b>required 但 nullable</b>——键必须出现，
 * 值可以是显式 {@code null}。普通 record 绑定无法区分"键缺失"与"键为 null"
 * （Jackson 都置为 null，{@code @NotNull} 只能拦后者），因此与
 * {@link RolePermissionsUpdateRequestDeserializer} 同源，采用 record 级自定义
 * 反序列化器：缺失任一键返回 400，显式 null 按 nullable 语义放行。</p>
 *
 * <p>自定义反序列化器会绕过 BeanDeserializer，全局
 * {@code fail-on-unknown-properties} 不作用于它，所以本类<b>显式</b>校验
 * 未知字段、键缺失与字段 JSON 类型（{@code permissionName} 必须字符串；
 * {@code path}/{@code method} 必须字符串或 null）。取值枚举（method 仅 7 个
 * 大写方法或 null）、长度上限与"按数据库已有 permissionType 的条件不变量"
 * 由服务层校验（需要读库）。</p>
 */
public class PermissionUpdateRequestDeserializer extends ValueDeserializer<PermissionUpdateRequest> {

    private static final Set<String> ALLOWED_FIELDS = Set.of("permissionName", "path", "method");

    @Override
    public PermissionUpdateRequest deserialize(tools.jackson.core.JsonParser parser,
            DeserializationContext context) {
        JsonNode root = parser.readValueAsTree();
        if (root == null || !root.isObject()) {
            return context.reportInputMismatch(PermissionUpdateRequest.class, "请求必须是 JSON 对象");
        }
        // 自定义反序列化器绕过 BeanDeserializer：必须自己拒绝未知字段
        for (var field : root.properties()) {
            if (!ALLOWED_FIELDS.contains(field.getKey())) {
                return context.reportInputMismatch(PermissionUpdateRequest.class,
                        "请求不接受未知字段：%s", field.getKey());
            }
        }
        // required: [permissionName, path, method] —— 键缺失 400（与显式 null 区分）
        for (String required : ALLOWED_FIELDS) {
            if (!root.has(required)) {
                return context.reportInputMismatch(PermissionUpdateRequest.class,
                        "字段 %s 不能缺失（完整 PUT 语义）", required);
            }
        }

        JsonNode name = root.get("permissionName");
        if (name == null || !name.isString()) {
            return context.reportInputMismatch(PermissionUpdateRequest.class,
                    "字段 permissionName 必须是非空 JSON 字符串");
        }
        JsonNode path = root.get("path");
        if (!path.isNull() && !path.isString()) {
            return context.reportInputMismatch(PermissionUpdateRequest.class,
                    "字段 path 必须是 JSON 字符串或 null");
        }
        JsonNode method = root.get("method");
        if (!method.isNull() && !method.isString()) {
            return context.reportInputMismatch(PermissionUpdateRequest.class,
                    "字段 method 必须是 JSON 字符串或 null");
        }
        return new PermissionUpdateRequest(name.stringValue(),
                path.isNull() ? null : path.stringValue(),
                method.isNull() ? null : method.stringValue());
    }
}
