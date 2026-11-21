package com.xinghe.cms.user.dto;

import java.util.Set;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ValueDeserializer;

/** {@link DepartmentStatusUpdateRequest} 专用严格反序列化器。 */
public class DepartmentStatusUpdateRequestDeserializer
        extends ValueDeserializer<DepartmentStatusUpdateRequest> {

    private static final Set<String> FIELDS = Set.of("status");

    @Override
    public DepartmentStatusUpdateRequest deserialize(JsonParser parser,
            DeserializationContext context) {
        JsonNode root = parser.readValueAsTree();
        DepartmentJson.requireObject(root, DepartmentStatusUpdateRequest.class, context, FIELDS);
        return new DepartmentStatusUpdateRequest(
                DepartmentJson.status(root, DepartmentStatusUpdateRequest.class, context));
    }
}
