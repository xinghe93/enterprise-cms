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
 * 角色权限关联表数据访问。复合主键 {@code (role_id, permission_id)}，不使用
 * {@code selectById}；全量替换相关的读回显、当前集合、差集删除与差集插入 SQL
 * 集中在下方注解中（与 B 模块其他 Mapper 的注解 script 风格一致），由 Service
 * 在单个事务内调用；{@code created_at} 由数据库默认值维护，应用侧不写入。
 *
 * <p><b>差集替换（评审 P2）</b>：全量替换不再"先 {@code DELETE WHERE role_id}
 * 清空、再整批插入"。MySQL 默认 REPEATABLE READ 下，关联表为空时两个不同角色
 * 的事务各自执行一次无匹配的 {@code DELETE} 会锁住同一个索引间隙（gap lock
 * 之间兼容），随后两次 {@code INSERT} 的插入意向锁互相等待而死锁。改为按当前
 * 集合与目标集合的差集：<b>只按完整复合主键删除确实需要移除的关联行、只插入
 * 确实新增的项</b>；空差集不发 DELETE，空表首次分配因此完全没有 DELETE，两个
 * 事务的 INSERT 不冲突。删除与插入均按 permission_id 升序（协作约定 8.1
 * "批量按 ID 顺序执行"），任一失败整体回滚。</p>
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
     * 差集计算（{@code toRemove = current - target}、{@code toAdd = target - current}）。
     * 这是普通一致性读（非锁定读），空表时不加锁、不产生间隙锁。
     */
    @Select("""
            SELECT permission_id
            FROM sys_role_permission
            WHERE role_id = #{roleId}
            ORDER BY permission_id ASC
            """)
    List<Long> selectPermissionIdsByRoleId(@Param("roleId") Long roleId);

    /**
     * 按<b>完整复合主键</b>删除确实需要移除的关联行（差集 {@code current - target}）。
     *
     * <p>调用方保证 {@code permissionIds} 非空、每项都存在于当前关联中且按 ID
     * 升序（固定删除顺序）。{@code role_id} 为主键前缀、{@code permission_id}
     * 为完整主键第二列，命中唯一记录时只加行锁，不会像"无匹配的
     * {@code DELETE WHERE role_id = ?}"那样锁住整个索引间隙；空差集由 Service
     * 跳过、不发出本条 SQL（评审 P2 的关键）。空数组清空时本方法收到当前全部
     * 关联 ID，效果等价于清空。</p>
     */
    @Delete("""
            <script>
            DELETE FROM sys_role_permission
            WHERE role_id = #{roleId}
              AND permission_id IN
              <foreach collection="permissionIds" item="pid" open="(" separator="," close=")">#{pid}</foreach>
            </script>
            """)
    int deletePermissions(@Param("roleId") Long roleId,
            @Param("permissionIds") List<Long> permissionIds);

    /**
     * 批量插入<b>差集新增</b>的角色权限关联（{@code target - current}）。
     * {@code created_at} 由数据库默认值维护，不写入。调用方保证
     * {@code permissionIds} 非空、按 ID 升序去重且均为当前不存在的项（固定插入
     * 顺序，与按 ID 升序的共享锁配合）；空差集由服务层跳过、不发出本条 SQL。
     * 空表首次分配时只有本条 INSERT、没有 DELETE，两个不同角色的事务互不加
     * 间隙锁，插入意向锁不冲突（评审 P2）。
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
