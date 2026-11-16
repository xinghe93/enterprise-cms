package com.xinghe.cms.user.dto;

import java.util.Map;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ValueDeserializer;

/**
 * 登录请求专用的严格反序列化器（只挂在 {@link LoginRequest} 上，不影响其他模块）。
 *
 * <p>契约《接口与协作约定》8.1 与 OpenAPI 的 {@code LoginRequest}：
 * username / password 的 JSON 类型都必须是 {@code string}，未知字段、null、
 * 数字、布尔、数组、对象一律 400。Jackson 默认会把数字/布尔强制转换成 String
 * 并送进登录服务，这里在绑定阶段就拒绝，异常由 Spring MVC 转成 400，
 * Controller 与 {@code AuthService} 完全不会被调用。</p>
 *
 * <p>校验刻意放在 DTO 级别而不是全局 {@code JsonMapper} / CoercionConfig：
 * 改全局反序列化设置会改变 B/C/D 所有 DTO 的行为，属于跨模块影响。</p>
 *
 * <p>长度与非空仍由 {@code @NotBlank / @NotNull / @Size} 在 Controller 的
 * {@code @Valid} 阶段校验；密码按契约不 trim，{@link JsonNode#textValue()} 原样返回。</p>
 */
public class LoginRequestDeserializer extends ValueDeserializer<LoginRequest> {

    @Override
    public LoginRequest deserialize(JsonParser parser, DeserializationContext context) {
        JsonNode root = parser.readValueAsTree();
        if (root == null || !root.isObject()) {
            return context.reportInputMismatch(LoginRequest.class, "登录请求必须是 JSON 对象");
        }
        // 自定义反序列化器不经过 BeanDeserializer，未知字段必须在这里自行拒绝，
        // 不能依赖全局 fail-on-unknown-properties 开关。
        for (Map.Entry<String, JsonNode> field : root.properties()) {
            if (!"username".equals(field.getKey()) && !"password".equals(field.getKey())) {
                return context.reportInputMismatch(LoginRequest.class,
                        "登录请求不接受未知字段：%s", field.getKey());
            }
        }
        return new LoginRequest(
                requireString(root, "username", context),
                requireString(root, "password", context));
    }

    private String requireString(JsonNode root, String name, DeserializationContext context) {
        JsonNode value = root.get(name);
        if (value == null || !value.isString()) {
            return context.reportInputMismatch(LoginRequest.class,
                    "字段 %s 必须是 JSON 字符串", name);
        }
        // 不 trim、不截断：密码的首尾空白是凭据的一部分，必须原样送哈希校验。
        return value.textValue();
    }
}
