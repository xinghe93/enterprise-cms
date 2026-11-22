package com.xinghe.cms.permission.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.xinghe.cms.common.json.StrictStringDeserializer;
import com.xinghe.cms.permission.validation.ContractNotBlank;
import jakarta.validation.constraints.NotNull;
import tools.jackson.databind.annotation.JsonDeserialize;

/**
 * 创建权限项请求体（OpenAPI {@code PermissionCreate} schema，
 * {@code POST /api/admin/permissions}）。
 *
 * <p>契约：{@code permissionCode}/{@code permissionName}/{@code permissionType}
 * 必填；{@code path}/{@code method} 可缺省、可显式 null——缺省与 null 在
 * 后续校验中行为一致（API 类型二者必填，MENU/BUTTON 的 method 必须 null），
 * 因此用普通 record + Bean Validation 即可，无需 record 级自定义反序列化器。
 * 未知字段由 Jackson 全局严格模式拦截 400；字段类型错误（数字形式的编码、
 * 布尔形式的路径）经 {@link StrictStringDeserializer} 拒绝为 400。</p>
 *
 * <p>{@code pattern \S}（至少一个非空白字符）由 {@link ContractNotBlank} 表达
 * （按契约 ECMAScript {@code \s} 判空白，与服务层去空白共用同一真源）；长度
 * 上限（100/100/255/16）不在 Bean Validation 中校验——{@code @Size} 按
 * UTF-16 代码单元统计会把增补字符重复计数，由服务层按 Unicode 码点校验。
 * {@code permissionType} 的枚举取值（MENU/BUTTON/API）与类型条件不变量由
 * 服务层校验（需结合 path/method 的组合判断）。</p>
 *
 * <p>语义约束（服务层执行，协作约定 8.1 / OpenAPI oneOf）：编码唯一且创建后
 * 不可改，默认 status=1；API 类型必须提供 {@code /api/auth}、{@code /api/admin}
 * 或 {@code /api/portal} 下不含查询串/片段/空白的路径及大写 HTTP 方法；
 * MENU/BUTTON 的 method 必须为 null。</p>
 *
 * @param permissionCode 权限编码，唯一、创建后不可改
 * @param permissionName 权限名称
 * @param permissionType 类型：MENU / BUTTON / API
 * @param path           菜单路由或接口路径，可空（API 类型必填且受前缀约束）
 * @param method         接口 HTTP 方法，可空（API 类型必填，MENU/BUTTON 必须 null）
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record PermissionCreateRequest(
        @ContractNotBlank(message = "权限编码不能为空白")
        @JsonDeserialize(using = StrictStringDeserializer.class)
        String permissionCode,
        @ContractNotBlank(message = "权限名称不能为空白")
        @JsonDeserialize(using = StrictStringDeserializer.class)
        String permissionName,
        @NotNull(message = "权限类型不能为空")
        @JsonDeserialize(using = StrictStringDeserializer.class)
        String permissionType,
        @JsonDeserialize(using = StrictStringDeserializer.class)
        String path,
        @JsonDeserialize(using = StrictStringDeserializer.class)
        String method) {
}
