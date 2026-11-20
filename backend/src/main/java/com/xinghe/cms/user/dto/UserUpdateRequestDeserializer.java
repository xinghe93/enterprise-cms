package com.xinghe.cms.user.dto;

import java.util.Set;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ValueDeserializer;

/** {@link UserUpdateRequest} 专用严格反序列化器：完整 PUT 的两个字段都必填。 */
public class UserUpdateRequestDeserializer extends ValueDeserializer<UserUpdateRequest> {

    private static final Set<String> FIELDS = Set.of("realName", "departmentId");

    @Override
    public UserUpdateRequest deserialize(JsonParser parser, DeserializationContext context) {
        JsonNode root = parser.readValueAsTree();
        AdminJson.requireObject(root, UserUpdateRequest.class, context, FIELDS);
        return new UserUpdateRequest(
                AdminJson.text(root, "realName", 1, 100, true, UserUpdateRequest.class, context),
                AdminJson.nullableId(root, "departmentId", true, UserUpdateRequest.class, context));
    }
}
