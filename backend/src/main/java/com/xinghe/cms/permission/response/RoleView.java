package com.xinghe.cms.permission.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.xinghe.cms.permission.entity.SysRole;
import java.time.LocalDateTime;

/**
 * 角色对外视图：列表与详情共用（OpenAPI {@code Role} schema）。
 *
 * <p>契约要求 JSON 中业务 ID 使用十进制字符串（{@code ApiResponse} 不自动转换，
 * 由 VO 自行输出），因此 {@code id} 为 {@link String}。时间格式固定为
 * {@code yyyy-MM-dd'T'HH:mm:ss.SSS}，对应数据库 DATETIME(3)。</p>
 *
 * @param id       角色 ID 的十进制字符串
 * @param roleCode 角色编码
 * @param roleName 角色名称
 * @param status   状态：0 停用，1 启用
 * @param createdAt 创建时间
 * @param updatedAt 更新时间
 */
@JsonFormat(shape = JsonFormat.Shape.STRING)
public record RoleView(
        String id,
        String roleCode,
        String roleName,
        Integer status,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS") LocalDateTime createdAt,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS") LocalDateTime updatedAt) {

    /**
     * 由实体组装视图；ID 转为十进制字符串，其余字段原样透传。
     */
    public static RoleView from(SysRole role) {
        return new RoleView(
                String.valueOf(role.getId()),
                role.getRoleCode(),
                role.getRoleName(),
                role.getStatus(),
                role.getCreatedAt(),
                role.getUpdatedAt());
    }
}
