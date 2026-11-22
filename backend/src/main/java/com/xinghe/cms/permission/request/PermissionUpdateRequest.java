package com.xinghe.cms.permission.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import tools.jackson.databind.annotation.JsonDeserialize;

/**
 * 修改权限项请求体（OpenAPI {@code PermissionUpdate} schema，
 * {@code PUT /api/admin/permissions/{id}}）。
 *
 * <p>契约：完整 PUT，{@code permissionName}/{@code path}/{@code method} 三个键
 * 都必须出现（缺失 400，由 record 级反序列化器区分"键缺失"与"显式 null"）；
 * 其中 {@code path}/{@code method} 是 required 但 nullable——显式 null 合法
 * （MENU/BUTTON 类型的 method 即 null）。编码与 {@code permissionType} 不在
 * 请求体中（不接受未知字段），不可修改；按<b>数据库中已有类型</b>执行与创建
 * 相同的条件不变量（API：path 非空有效 + method 非空大写；MENU/BUTTON：
 * method 必须 null），由服务层读库后校验。</p>
 *
 * <p>长度上限（100/255/16，OpenAPI maxLength 按字符数）由服务层按 Unicode
 * 码点校验；method 的取值枚举（7 个大写方法或 null）亦由服务层校验。</p>
 *
 * @param permissionName 权限名称，必填
 * @param path           菜单路由或接口路径，键必填、值可空（API 类型必须有效路径）
 * @param method         接口 HTTP 方法，键必填、值可空（API 类型必须大写方法，MENU/BUTTON 必须 null）
 */
@JsonIgnoreProperties(ignoreUnknown = false)
@JsonDeserialize(using = PermissionUpdateRequestDeserializer.class)
public record PermissionUpdateRequest(
        String permissionName,
        String path,
        String method) {
}
