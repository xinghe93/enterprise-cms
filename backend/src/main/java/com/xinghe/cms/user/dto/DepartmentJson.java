package com.xinghe.cms.user.dto;

import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JsonNode;

/**
 * 部门管理请求体的严格 JSON 校验工具（仅本包内使用）。
 *
 * <p>与员工侧 {@code AdminJson} 同构但独立存在：两条功能分支都从最新 dev 开出，
 * 互相不能引用对方尚未合入的类，因此这里不复用、也不修改公共配置文件。</p>
 *
 * <p>契约 8.1：类型按 OpenAPI 严格匹配，未知字段 / null / 非法 ID / 非法数字一律 400；
 * 名称与编码先去掉首尾空白（{@link String#strip()} 覆盖 U+3000 全角空格，
 * {@code trim()} 只处理 U+0020 及以下的 ASCII 空白），再按最终值校验长度与非空。</p>
 */
final class DepartmentJson {

    static final Pattern ID_PATTERN = Pattern.compile("^[1-9][0-9]{0,18}$");
    static final int MAX_SORT_ORDER = 2_147_483_647;

    private DepartmentJson() {
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

    static String text(JsonNode root, String field, int max, Class<?> target,
            DeserializationContext context) {
        JsonNode value = root.get(field);
        if (value == null || !value.isString()) {
            return context.reportInputMismatch(target, "字段 %s 必须是 JSON 字符串", field);
        }
        String raw = value.textValue();
        // strip() 按 Unicode 判定空白，能去掉 U+3000 全角空格；trim() 不能，
        // 纯全角空格会因此通过非空校验（P2 审查）。先 strip 再按最终值校验长度。
        String trimmed = raw.strip();
        if (trimmed.isEmpty() || trimmed.length() > max) {
            return context.reportInputMismatch(target, "字段 %s 长度必须在 1 到 %d 之间",
                    field, max);
        }
        return trimmed;
    }

    /** 可为 null 的 ID 字段；required 控制字段本身是否必须出现。 */
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
        if (!value.isString() || !ID_PATTERN.matcher(value.textValue()).matches()) {
            return context.reportInputMismatch(target,
                    "字段 %s 必须是十进制正整数字符串 ID", field);
        }
        try {
            return Long.parseLong(value.textValue());
        } catch (NumberFormatException exception) {
            return context.reportInputMismatch(target, "字段 %s 超出允许范围", field);
        }
    }

    /** sortOrder：JSON 整数 0～2147483647；创建省略为 0，修改必填。 */
    static int sortOrder(JsonNode root, String field, boolean required, Class<?> target,
            DeserializationContext context) {
        if (!root.has(field)) {
            if (required) {
                return context.reportInputMismatch(target, "字段 %s 不能缺失", field);
            }
            return 0;
        }
        JsonNode value = root.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
            return context.reportInputMismatch(target,
                    "字段 %s 必须是 0 到 2147483647 之间的 JSON 整数", field);
        }
        int result = value.intValue();
        if (result < 0) {
            return context.reportInputMismatch(target, "字段 %s 不能为负数", field);
        }
        return result;
    }

    /** StatusUpdate：只接受 JSON 整数 0 或 1。 */
    static int status(JsonNode root, Class<?> target, DeserializationContext context) {
        JsonNode value = root.get("status");
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
            return context.reportInputMismatch(target, "字段 status 必须是 JSON 整数 0 或 1");
        }
        int result = value.intValue();
        if (result != 0 && result != 1) {
            return context.reportInputMismatch(target, "字段 status 只能是 0 或 1");
        }
        return result;
    }
}
