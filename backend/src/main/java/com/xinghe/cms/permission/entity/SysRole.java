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
 * 系统角色，对应表 {@code sys_role}。
 *
 * <p>{@code roleCode} 全局唯一（uk_role_code）；{@code status} 为 0 停用、1 启用
 * （chk_role_status 约束）。{@code createdAt}/{@code updatedAt} 由数据库默认值维护，
 * 应用侧不写入。</p>
 */
@Getter
@Setter
@TableName("sys_role")
public class SysRole {

    /** 角色 ID，自增主键。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 角色编码，唯一，最长 64，如 ADMIN、EDITOR。 */
    @TableField("role_code")
    private String roleCode;

    /** 角色名称，最长 100。 */
    @TableField("role_name")
    private String roleName;

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
