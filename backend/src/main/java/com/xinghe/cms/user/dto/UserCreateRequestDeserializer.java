package com.xinghe.cms.user.dto;

import java.util.Set;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ValueDeserializer;

/** {@link UserCreateRequest} 专用严格反序列化器：只作用于该 DTO，不影响全局 Jackson。 */
public class UserCreateRequestDeserializer extends ValueDeserializer<UserCreateRequest> {

    private static final Set<String> FIELDS =
            Set.of("username", "password", "realName", "departmentId");

    @Override
    public UserCreateRequest deserialize(JsonParser parser, DeserializationContext context) {
        JsonNode root = parser.readValueAsTree();
        AdminJson.requireObject(root, UserCreateRequest.class, context, FIELDS);
        return new UserCreateRequest(
                AdminJson.text(root, "username", 1, 64, true, UserCreateRequest.class, context),
                // 契约 8.1：密码不 trim、不静默截断，原样交给 PasswordHasher。
                AdminJson.text(root, "password", 8, 128, false, UserCreateRequest.class, context),
                AdminJson.text(root, "realName", 1, 100, true, UserCreateRequest.class, context),
                AdminJson.nullableId(root, "departmentId", false, UserCreateRequest.class, context));
    }
}
