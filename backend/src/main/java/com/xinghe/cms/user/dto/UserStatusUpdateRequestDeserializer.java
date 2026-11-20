package com.xinghe.cms.user.dto;

import java.util.Map;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ValueDeserializer;

/**
 * {@link UserStatusUpdateRequest} 专用的严格反序列化器，只作用于该 DTO。
 *
 * <p>OpenAPI {@code StatusUpdate} 只声明一个必填字段 {@code status}（integer，enum 0/1），
 * 且 {@code additionalProperties=false}。数字以外的类型、缺失、null、越界值和未知字段
 * 都在绑定阶段变成 {@code HttpMessageNotReadableException} → 400，Controller 与
 * {@code UserStatusService} 完全不会被调用。</p>
 */
public class UserStatusUpdateRequestDeserializer extends ValueDeserializer<UserStatusUpdateRequest> {

    @Override
    public UserStatusUpdateRequest deserialize(JsonParser parser, DeserializationContext context) {
        JsonNode root = parser.readValueAsTree();
        if (root == null || !root.isObject()) {
            return context.reportInputMismatch(UserStatusUpdateRequest.class, "启停请求必须是 JSON 对象");
        }
        for (Map.Entry<String, JsonNode> field : root.properties()) {
            if (!"status".equals(field.getKey())) {
                return context.reportInputMismatch(UserStatusUpdateRequest.class,
                        "启停请求不接受未知字段：%s", field.getKey());
            }
        }
        JsonNode value = root.get("status");
        // isIntegralNumber 排除 "1"、1.0、true、null、数组与对象；
        // canConvertToInt 再挡住超出 int 范围的整数（例如 99999999999999）。
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
            return context.reportInputMismatch(UserStatusUpdateRequest.class,
                    "字段 status 必须是 JSON 整数 0 或 1");
        }
        int status = value.intValue();
        if (status != UserStatusUpdateRequest.DISABLED && status != UserStatusUpdateRequest.ENABLED) {
            return context.reportInputMismatch(UserStatusUpdateRequest.class,
                    "字段 status 只能是 0 或 1");
        }
        return new UserStatusUpdateRequest(status);
    }
}
