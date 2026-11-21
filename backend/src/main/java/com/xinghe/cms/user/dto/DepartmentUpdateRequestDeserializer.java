package com.xinghe.cms.user.dto;

import java.util.Set;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ValueDeserializer;

/** {@link DepartmentUpdateRequest} 专用严格反序列化器：三个字段都必填。 */
public class DepartmentUpdateRequestDeserializer extends ValueDeserializer<DepartmentUpdateRequest> {

    private static final Set<String> FIELDS = Set.of("parentId", "deptName", "sortOrder");

    @Override
    public DepartmentUpdateRequest deserialize(JsonParser parser, DeserializationContext context) {
        JsonNode root = parser.readValueAsTree();
        DepartmentJson.requireObject(root, DepartmentUpdateRequest.class, context, FIELDS);
        return new DepartmentUpdateRequest(
                DepartmentJson.nullableId(root, "parentId", true,
                        DepartmentUpdateRequest.class, context),
                DepartmentJson.text(root, "deptName", 100,
                        DepartmentUpdateRequest.class, context),
                DepartmentJson.sortOrder(root, "sortOrder", true,
                        DepartmentUpdateRequest.class, context));
    }
}
