package com.xinghe.cms.permission.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 授权快照只读查询 Mapper（B 模块维护）。
 *
 * <p>按《跨模块服务接口 V1.0》第 2 节，跨模块只读 JOIN 允许读取正式关系；
 * 本 Mapper 全部为 SELECT，仅供 {@code AuthorizationQueryService} 计算授权快照，
 * 不承担 sys_user / sys_user_role 的写入责任（归 A）。</p>
 */
@Mapper
public interface AuthorizationQueryMapper {

    /**
     * 读取用户启用状态。
     *
     * @return 1 启用，0 停用；用户不存在时返回 null
     */
    @Select("SELECT status FROM sys_user WHERE id = #{userId}")
    Integer selectUserStatus(@Param("userId") Long userId);

    /**
     * 查询用户关联的启用角色编码（过滤停用角色）。
     *
     * <p>启用但没有任何权限的角色同样会出现在结果里，由服务层保持原样。</p>
     */
    @Select("""
            SELECT DISTINCT r.role_code
            FROM sys_role r
            JOIN sys_user_role ur ON ur.role_id = r.id
            WHERE ur.user_id = #{userId}
              AND r.status = 1
            ORDER BY r.role_code
            """)
    List<String> selectEnabledRoleCodes(@Param("userId") Long userId);

    /**
     * 查询用户的有效权限编码：启用角色 × 角色权限 × 启用权限。
     *
     * <p>停用角色、停用权限均被过滤；角色停用但其权限被其他启用角色共享时，
     * 权限仍有效（按角色逐一判定）。</p>
     */
    @Select("""
            SELECT DISTINCT p.permission_code
            FROM sys_permission p
            JOIN sys_role_permission rp ON rp.permission_id = p.id
            JOIN sys_user_role ur ON ur.role_id = rp.role_id
            JOIN sys_role r ON r.id = rp.role_id
            WHERE ur.user_id = #{userId}
              AND r.status = 1
              AND p.status = 1
            ORDER BY p.permission_code
            """)
    List<String> selectPermissionCodes(@Param("userId") Long userId);
}
