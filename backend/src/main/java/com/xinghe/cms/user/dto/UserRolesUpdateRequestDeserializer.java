package com.xinghe.cms.user.dto;

import java.util.Set;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ValueDeserializer;

/** {@link UserRolesUpdateRequest} 专用严格反序列化器：数组、去重、数量与 ID 格式。 */
public class UserRolesUpdateRequestDeserializer extends ValueDeserializer<UserRolesUpdateRequest> {

    private static final Set<String> FIELDS = Set.of("roleIds");

    @Override
    public UserRolesUpdateRequest deserialize(JsonParser parser, DeserializationContext context) {
        JsonNode root = parser.readValueAsTree();
        AdminJson.requireObject(root, UserRolesUpdateRequest.class, context, FIELDS);
        return new UserRolesUpdateRequest(
                AdminJson.idList(root, "roleIds", UserRolesUpdateRequest.class, context));
    }
}
