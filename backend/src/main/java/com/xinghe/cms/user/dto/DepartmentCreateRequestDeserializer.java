package com.xinghe.cms.user.dto;

import java.util.Set;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ValueDeserializer;

/** {@link DepartmentCreateRequest} 专用严格反序列化器。 */
public class DepartmentCreateRequestDeserializer extends ValueDeserializer<DepartmentCreateRequest> {

    private static final Set<String> FIELDS =
            Set.of("parentId", "deptCode", "deptName", "sortOrder");

    @Override
    public DepartmentCreateRequest deserialize(JsonParser parser, DeserializationContext context) {
        JsonNode root = parser.readValueAsTree();
        DepartmentJson.requireObject(root, DepartmentCreateRequest.class, context, FIELDS);
        return new DepartmentCreateRequest(
                DepartmentJson.nullableId(root, "parentId", false,
                        DepartmentCreateRequest.class, context),
                DepartmentJson.text(root, "deptCode", 64, DepartmentCreateRequest.class, context),
                DepartmentJson.text(root, "deptName", 100, DepartmentCreateRequest.class, context),
                DepartmentJson.sortOrder(root, "sortOrder", false,
                        DepartmentCreateRequest.class, context));
    }
}
