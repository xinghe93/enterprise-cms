package com.xinghe.cms.permission.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.xinghe.cms.permission.entity.SysPermission;
import java.time.LocalDateTime;

/**
 * 权限项对外视图（OpenAPI {@code Permission} schema），供
 * {@code GET /api/admin/roles/{id}/permissions} 管理回显使用。
 *
 * <p>契约：返回角色<b>全部已分配权限（含停用项）</b>及每项真实 status，按
 * permissionId 升序；该列表只用于界面回显，绝不能代替
 * {@code AuthorizationSnapshot} 做鉴权。ID 使用十进制字符串（与 RoleView
 * 一致，{@code ApiResponse} 不自动转换，由 VO 自行输出）；时间格式固定
 * {@code yyyy-MM-dd'T'HH:mm:ss.SSS}，对应数据库 DATETIME(3)。</p>
 *
 * <p>{@code path}/{@code method} 在 OpenAPI 中是 nullable，MENU/BUTTON 类权限
 * 的 method 为 null，原样透传（不伪造空串）。</p>
 *
 * @param id              权限 ID 的十进制字符串
 * @param permissionCode  权限编码
 * @param permissionName  权限名称
 * @param permissionType  类型：MENU / BUTTON / API
 * @param path            菜单路由或接口路径，可空
 * @param method          接口 HTTP 方法，可空
 * @param status          状态：0 停用，1 启用（回显保留停用项）
 * @param createdAt       创建时间
 * @param updatedAt       更新时间
 */
public record PermissionView(
        String id,
        String permissionCode,
        String permissionName,
        String permissionType,
        String path,
        String method,
        Integer status,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS") LocalDateTime createdAt,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS") LocalDateTime updatedAt) {

    /** 由实体组装视图；ID 转十进制字符串，其余字段（含可空的 path/method）原样透传。 */
    public static PermissionView from(SysPermission permission) {
        return new PermissionView(
                String.valueOf(permission.getId()),
                permission.getPermissionCode(),
                permission.getPermissionName(),
                permission.getPermissionType(),
                permission.getPath(),
                permission.getMethod(),
                permission.getStatus(),
                permission.getCreatedAt(),
                permission.getUpdatedAt());
    }
}
