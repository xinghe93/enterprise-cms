package com.xinghe.cms.permission.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 角色权限关联，对应表 {@code sys_role_permission}。
 *
 * <p>复合主键 {@code (role_id, permission_id)}，无独立状态列：关联是否生效取决于
 * 角色与权限各自的 status，因此"替换角色权限时停用项拒绝"只能在服务层 JOIN 校验。
 * 行由数据库默认值记录创建时间，应用侧不写入；删除采用物理删除。</p>
 */
@Getter
@Setter
@TableName("sys_role_permission")
public class SysRolePermission {

    /** 角色 ID，联合主键之一，外键 fk_role_permission_role。 */
    @TableField("role_id")
    private Long roleId;

    /** 权限 ID，联合主键之一，外键 fk_role_permission_permission。 */
    @TableField("permission_id")
    private Long permissionId;

    /** 创建时间，数据库默认值维护。 */
    @TableField(value = "created_at", insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createdAt;
}
