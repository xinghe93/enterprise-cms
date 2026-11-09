package com.xinghe.cms.permission.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 系统权限，对应表 {@code sys_permission}。
 *
 * <p>{@code permissionCode} 全局唯一（uk_permission_code）；{@code permissionType}
 * 严格为 MENU（菜单）、BUTTON（按钮）、API（接口）之一（chk_permission_type 约束）；
 * {@code status} 为 0 停用、1 启用。{@code path}/{@code method} 仅 API/MENU 类需要，
 * API 的 path 必须以 {@code /api/} 开头（应用层校验）。时间字段由数据库维护。</p>
 */
@Getter
@Setter
@TableName("sys_permission")
public class SysPermission {

    /** 权限 ID，自增主键。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 权限编码，唯一，最长 100，如 role:list。 */
    @TableField("permission_code")
    private String permissionCode;

    /** 权限名称，最长 100。 */
    @TableField("permission_name")
    private String permissionName;

    /** 权限类型：MENU 菜单，BUTTON 按钮，API 接口。 */
    @TableField("permission_type")
    private String permissionType;

    /** 菜单路由或接口路径，可空。 */
    @TableField("path")
    private String path;

    /** 接口 HTTP 方法，可空，如 GET、PUT。 */
    @TableField("method")
    private String method;

    /** 状态：0 停用，1 启用。 */
    @TableField("status")
    private Integer status;

    /** 创建时间，数据库默认值维护。 */
    @TableField(value = "created_at", insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createdAt;

    /** 更新时间，数据库 ON UPDATE 维护。 */
    @TableField(value = "updated_at", insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime updatedAt;
}
