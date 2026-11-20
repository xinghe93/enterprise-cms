package com.xinghe.cms.permission.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xinghe.cms.permission.entity.SysPermission;
import com.xinghe.cms.permission.entity.SysRolePermission;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 角色权限关联表数据访问。复合主键表，全部操作基于 Wrapper 条件
 * （selectList / delete by wrapper），不使用 selectById。
 *
 * <p>角色权限全量替换的读回显、幂等比较、删除与批量插入 SQL 集中在下方注解中
 * （与 B 模块其他 Mapper 的 {@code @Select} script 风格一致），在 Service 的单个
 * 事务内调用；{@code created_at} 由数据库默认值维护，应用侧不写入。</p>
 */
@Mapper
public interface SysRolePermissionMapper extends BaseMapper<SysRolePermission> {

    /**
     * 查询角色已分配的<b>全部权限（含停用项）</b>，按关联 ID（{@code permission_id}）
     * 升序，供 {@code GET /api/admin/roles/{id}/permissions} 管理回显。
     *
     * <p>不过滤 {@code p.status}：契约要求回显保留停用项及真实状态；该列表仅用于
     * 界面回显，不能代替 {@code AuthorizationSnapshot} 鉴权。JOIN 到
     * {@code sys_permission} 取完整字段，映射回 {@link SysPermission} 实体
     * （全局 {@code map-underscore-to-camel-case} 已开启）。未分配时返回空列表。</p>
     */
    @Select("""
            SELECT p.id, p.permission_code, p.permission_name, p.permission_type,
                   p.path, p.method, p.status, p.created_at, p.updated_at
            FROM sys_role_permission rp
            JOIN sys_permission p ON p.id = rp.permission_id
            WHERE rp.role_id = #{roleId}
            ORDER BY rp.permission_id ASC
            """)
    List<SysPermission> selectPermissionsByRoleId(@Param("roleId") Long roleId);

    /**
     * 查询角色当前已关联的权限 ID，按 {@code permission_id} 升序，用于全量替换的
     * 幂等比较（与去重升序后的请求集合直接 {@code equals} 判断是否需要写库）。
     */
    @Select("""
            SELECT permission_id
            FROM sys_role_permission
            WHERE role_id = #{roleId}
            ORDER BY permission_id ASC
            """)
    List<Long> selectPermissionIdsByRoleId(@Param("roleId") Long roleId);

    /** 物理删除角色的全部权限关联（全量替换的"清空"步骤，空数组清空也走这里）。 */
    @Delete("DELETE FROM sys_role_permission WHERE role_id = #{roleId}")
    int deleteByRoleId(@Param("roleId") Long roleId);

    /**
     * 批量插入角色权限关联。{@code created_at} 由数据库默认值维护，不写入。
     * 调用方保证 {@code permissionIds} 非空且已按 ID 升序去重（固定插入顺序，
     * 与按 ID 升序的共享锁配合，避免并发替换互锁）；空集合由服务层跳过、
     * 不发出本条 SQL。
     */
    @Insert("""
            <script>
            INSERT INTO sys_role_permission (role_id, permission_id) VALUES
            <foreach collection="permissionIds" item="pid" separator=",">(#{roleId}, #{pid})</foreach>
            </script>
            """)
    int insertPermissions(@Param("roleId") Long roleId,
            @Param("permissionIds") List<Long> permissionIds);
}
