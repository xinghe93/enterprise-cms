package com.xinghe.cms.permission.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import tools.jackson.databind.annotation.JsonDeserialize;

/**
 * 全量替换角色权限请求体（OpenAPI {@code RolePermissionsUpdate} schema，
 * {@code PUT /api/admin/roles/{id}/permissions}）。
 *
 * <p>契约：请求体仅 {@code permissionIds} 一个字段（{@code required}，
 * {@code additionalProperties: false}）；值为 {@code IdList}——0～100 个十进制正整数
 * 字符串 ID、不允许重复；空数组表示清空该角色的全部权限关联。</p>
 *
 * <p>反序列化走 {@link RolePermissionsUpdateRequestDeserializer}（record 级自定义，
 * 与 A 模块 {@code UserRolesUpdateRequest} 同源）：字段级 {@code @JsonDeserialize} +
 * Bean Validation 无法同时覆盖"逐元素严格字符串类型 + 整体去重/计数"，且自定义
 * 反序列化器会绕过 BeanDeserializer——未知字段、必填、null、非字符串元素、越界数字
 * 全部在绑定阶段由反序列化器显式拒绝为 400。全局
 * {@code fail-on-unknown-properties: true} 与 {@link JsonIgnoreProperties} 对自定义
 * 反序列化器不生效，故此处加注解仅表明意图，实际拦截在反序列化器内。</p>
 *
 * <p>服务层拿到的是已校验、已去重的 {@code List<Long>}；业务校验（权限存在 404、
 * 停用 409、角色存在 404）在单事务内进行，与 DTO 绑定校验职责分离。</p>
 *
 * @param permissionIds 目标权限 ID 集合（已去十进制正整数、去重），空表示清空
 */
@JsonIgnoreProperties(ignoreUnknown = false)
@JsonDeserialize(using = RolePermissionsUpdateRequestDeserializer.class)
public record RolePermissionsUpdateRequest(List<Long> permissionIds) {
}
