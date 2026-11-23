package com.xinghe.cms.permission.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import tools.jackson.databind.annotation.JsonDeserialize;

/**
 * 全量替换角色权限请求体（OpenAPI {@code RolePermissionsUpdate} schema，
 * {@code PUT /api/admin/roles/{id}/permissions}）。
 *
 * <p>契约：{@code permissionIds}（{@code required}）值为 {@code IdList}——0～100 个
 * 十进制正整数字符串 ID、不允许重复；空数组表示清空该角色的全部权限关联。
 * {@code expectedPermissionIds}（可选）同样是 {@code IdList}，表示客户端打开编辑弹窗
 * 时看到的<b>原权限集合</b>；服务端在角色行锁内把它与当前集合比对，不一致返回 409，
 * 防止陈旧快照的全量替换覆盖其他管理员刚做的增删（并发 lost update，评审 P1）。
 * 字段缺省（老客户端/内部调用）为 {@code null}，跳过基线校验，行为与旧版完全一致。</p>
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
 * @param permissionIds         目标权限 ID 集合（已去十进制正整数、去重），空表示清空
 * @param expectedPermissionIds 客户端编辑开始时的原权限集合（可选，null 表示跳过并发校验）
 */
@JsonIgnoreProperties(ignoreUnknown = false)
@JsonDeserialize(using = RolePermissionsUpdateRequestDeserializer.class)
public record RolePermissionsUpdateRequest(List<Long> permissionIds, List<Long> expectedPermissionIds) {

    /** 兼容旧调用方：不带并发基线的请求（缺省/null 由服务端跳过校验）。 */
    public RolePermissionsUpdateRequest(List<Long> permissionIds) {
        this(permissionIds, null);
    }
}
