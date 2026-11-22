package com.xinghe.cms.permission.request;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ValueDeserializer;

/**
 * {@link RolePermissionsUpdateRequest} 的严格反序列化器（OpenAPI {@code IdList} schema）。
 *
 * <p>契约 8.1 要求"未知 JSON 字段、非字符串 ID、越界数字、null 一律在绑定阶段变成 400"。
 * 本接口请求体只有一个字段，且是 ID 数组（0～100 项、每项十进制正整数字符串、不允许重复），
 * 无法用字段级 {@code @JsonDeserialize} + Bean Validation 完整覆盖（既要逐元素严格类型，
 * 又要整体去重/计数），因此与 A 模块 {@code UserRolesUpdateRequest} 同源，采用 record 级
 * 自定义反序列化器。自定义反序列化器会绕过 BeanDeserializer，全局
 * {@code fail-on-unknown-properties} 与 {@code @JsonIgnoreProperties} 都不作用于它，
 * 所以本类<b>显式</b>校验未知字段、必填、null 与元素类型，缺一不可。</p>
 *
 * <p>ID 规则与 {@code common.request.Ids} / OpenAPI {@code Id} 完全一致：
 * {@code ^[1-9][0-9]{0,18}$}（无前导零、最长 19 位）；{@code Long.parseLong} 的
 * 溢出按 400 处理，不能落到 500。重复 ID 违反 {@code uniqueItems: true}，同样 400。</p>
 *
 * <p>输出为去重后按原顺序保留的 {@code List<Long>}（{@link LinkedHashSet}）；服务层
 * 会再按 ID 升序排序以固定加锁顺序。空数组合法，表示清空关联。</p>
 */
public class RolePermissionsUpdateRequestDeserializer extends ValueDeserializer<RolePermissionsUpdateRequest> {

    private static final Set<String> ALLOWED_FIELDS = Set.of("permissionIds");

    /** 与 OpenAPI {@code Id} 一致：十进制正整数字符串，无前导零，最长 19 位。 */
    private static final Pattern ID_PATTERN = Pattern.compile("[1-9][0-9]{0,18}");

    /** OpenAPI {@code IdList.maxItems}。 */
    private static final int MAX_ITEMS = 100;

    @Override
    public RolePermissionsUpdateRequest deserialize(tools.jackson.core.JsonParser parser,
            DeserializationContext context) {
        JsonNode root = parser.readValueAsTree();
        if (root == null || !root.isObject()) {
            return context.reportInputMismatch(RolePermissionsUpdateRequest.class, "请求必须是 JSON 对象");
        }
        // 自定义反序列化器绕过 BeanDeserializer：必须自己拒绝未知字段
        for (var field : root.properties()) {
            if (!ALLOWED_FIELDS.contains(field.getKey())) {
                return context.reportInputMismatch(RolePermissionsUpdateRequest.class,
                        "请求不接受未知字段：%s", field.getKey());
            }
        }
        if (!root.has("permissionIds")) {
            return context.reportInputMismatch(RolePermissionsUpdateRequest.class, "字段 permissionIds 不能缺失");
        }
        JsonNode array = root.get("permissionIds");
        if (array == null || !array.isArray()) {
            return context.reportInputMismatch(RolePermissionsUpdateRequest.class,
                    "字段 permissionIds 必须是 JSON 数组");
        }
        if (array.size() > MAX_ITEMS) {
            return context.reportInputMismatch(RolePermissionsUpdateRequest.class,
                    "字段 permissionIds 最多包含 %d 个 ID", MAX_ITEMS);
        }

        Set<Long> ids = new LinkedHashSet<>();
        for (int i = 0; i < array.size(); i++) {
            JsonNode item = array.get(i);
            if (item == null || !item.isString() || !ID_PATTERN.matcher(item.stringValue()).matches()) {
                return context.reportInputMismatch(RolePermissionsUpdateRequest.class,
                        "字段 permissionIds 必须是十进制正整数字符串 ID");
            }
            long parsed;
            try {
                parsed = Long.parseLong(item.stringValue());
            } catch (NumberFormatException exception) {
                // 19 位仍可能超过 Long.MAX_VALUE：按 400 处理，不能落到 500
                return context.reportInputMismatch(RolePermissionsUpdateRequest.class,
                        "字段 permissionIds 的 ID 超出允许范围");
            }
            if (!ids.add(parsed)) {
                return context.reportInputMismatch(RolePermissionsUpdateRequest.class,
                        "字段 permissionIds 不能包含重复 ID");
            }
        }
        return new RolePermissionsUpdateRequest(List.copyOf(ids));
    }
}
