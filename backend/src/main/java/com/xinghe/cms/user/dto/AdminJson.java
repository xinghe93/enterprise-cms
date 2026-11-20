package com.xinghe.cms.user.dto;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JsonNode;

/**
 * A 模块后台管理请求体的严格 JSON 校验工具（仅本包内使用）。
 *
 * <p>契约《接口与协作约定》8.1：字段类型先按 OpenAPI schema 校验，未知字段、
 * null、非字符串 ID、越界数字一律在绑定阶段变成 400；普通名称/编码先按原始长度
 * 校验，再去首尾空白并验证非空；密码不 trim。自定义反序列化器绕过了
 * BeanDeserializer，所以这些规则必须显式实现，不能只依赖全局 Jackson 配置。</p>
 */
final class AdminJson {

    /** 与 OpenAPI {@code Id} / 契约 Id schema 一致：十进制正整数字符串，无前导零，最长 19 位。 */
    static final Pattern ID_PATTERN = Pattern.compile("^[1-9][0-9]{0,18}$");
    static final int ID_LIST_MAX = 100;

    private AdminJson() {
    }

    static void requireObject(JsonNode root, Class<?> target, DeserializationContext context,
            Set<String> allowedFields) {
        if (root == null || !root.isObject()) {
            context.reportInputMismatch(target, "请求必须是 JSON 对象");
            return;
        }
        for (Map.Entry<String, JsonNode> field : root.properties()) {
            if (!allowedFields.contains(field.getKey())) {
                context.reportInputMismatch(target, "请求不接受未知字段：%s", field.getKey());
            }
        }
    }

    static String text(JsonNode root, String field, int min, int max, boolean trim,
            Class<?> target, DeserializationContext context) {
        JsonNode value = root.get(field);
        if (value == null || !value.isString()) {
            return context.reportInputMismatch(target, "字段 %s 必须是 JSON 字符串", field);
        }
        String raw = value.textValue();
        if (raw.length() < min || raw.length() > max) {
            return context.reportInputMismatch(target, "字段 %s 长度必须在 %d 到 %d 之间",
                    field, min, max);
        }
        String result = trim ? raw.trim() : raw;
        if (trim && result.isEmpty()) {
            return context.reportInputMismatch(target, "字段 %s 不能为空白", field);
        }
        return result;
    }

    /** 可为 null 的 ID 字段；required 控制字段是否必须出现（出现但为 null 仍然合法）。 */
    static Long nullableId(JsonNode root, String field, boolean required, Class<?> target,
            DeserializationContext context) {
        if (!root.has(field)) {
            if (required) {
                return context.reportInputMismatch(target, "字段 %s 不能缺失", field);
            }
            return null;
        }
        JsonNode value = root.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        return parseId(value, field, target, context);
    }

    /** {@code IdList}：0～100 项、每项为合法 ID 字符串、不允许重复。 */
    static List<Long> idList(JsonNode root, String field, Class<?> target,
            DeserializationContext context) {
        if (!root.has(field)) {
            return context.reportInputMismatch(target, "字段 %s 不能缺失", field);
        }
        JsonNode value = root.get(field);
        if (value == null || !value.isArray()) {
            return context.reportInputMismatch(target, "字段 %s 必须是 JSON 数组", field);
        }
        if (value.size() > ID_LIST_MAX) {
            return context.reportInputMismatch(target, "字段 %s 最多包含 %d 个 ID",
                    field, ID_LIST_MAX);
        }
        Set<Long> ids = new LinkedHashSet<>();
        for (int index = 0; index < value.size(); index++) {
            Long parsed = parseId(value.get(index), field, target, context);
            if (!ids.add(parsed)) {
                return context.reportInputMismatch(target, "字段 %s 不能包含重复 ID", field);
            }
        }
        return List.copyOf(ids);
    }

    private static Long parseId(JsonNode value, String field, Class<?> target,
            DeserializationContext context) {
        if (value == null || !value.isString()
                || !ID_PATTERN.matcher(value.textValue()).matches()) {
            return context.reportInputMismatch(target,
                    "字段 %s 必须是十进制正整数字符串 ID", field);
        }
        try {
            return Long.parseLong(value.textValue());
        } catch (NumberFormatException exception) {
            // 19 位仍可能超过 Long.MAX_VALUE：按 400 处理，不能落到 500。
            return context.reportInputMismatch(target, "字段 %s 超出允许范围", field);
        }
    }
}
