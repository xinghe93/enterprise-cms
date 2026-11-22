package com.xinghe.cms.permission.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.xinghe.cms.permission.entity.SysPermission;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 权限表数据访问。单表 CRUD 由 MyBatis-Plus 提供，管理端分页筛选与
 * 锁定读等组合 SQL 以 @Select script 显式声明。
 */
@Mapper
public interface SysPermissionMapper extends BaseMapper<SysPermission> {

    /**
     * 权限项分页（管理端列表，OpenAPI {@code GET /api/admin/permissions}）：
     * keyword 为转义后的字面关键词，对 permission_code / permission_name 做
     * 包含匹配；status、permissionType 为精确筛选。排序遵循契约 8.2 列表
     * 默认规则：updated_at DESC, id DESC（id 兜底保证同时间戳下顺序稳定）。
     *
     * <p>调用方约定：keyword 已去首尾空白并完成 LIKE 通配符转义
     * （{@code !} 为转义字符），status 仅允许 0/1 或 null，
     * permissionType 仅允许 MENU/BUTTON/API 或 null。</p>
     *
     * @param page           MyBatis-Plus 分页参数（current/size），同时接收总数
     * @param keyword        转义后的关键词，null 表示不筛选
     * @param status         状态筛选，null 表示不筛选
     * @param permissionType 类型精确筛选，null 表示不筛选
     */
    @Select("""
            <script>
            SELECT id, permission_code, permission_name, permission_type, path, method,
                   status, created_at, updated_at
            FROM sys_permission
            <where>
            <if test="keyword != null">
            AND (permission_code LIKE CONCAT('%', #{keyword}, '%') ESCAPE '!'
            OR permission_name LIKE CONCAT('%', #{keyword}, '%') ESCAPE '!')
            </if>
            <if test="status != null">
            AND status = #{status}
            </if>
            <if test="permissionType != null">
            AND permission_type = #{permissionType}
            </if>
            </where>
            ORDER BY updated_at DESC, id DESC
            </script>
            """)
    Page<SysPermission> selectPermissionPage(Page<SysPermission> page,
            @Param("keyword") String keyword, @Param("status") Integer status,
            @Param("permissionType") String permissionType);

    /**
     * 按主键<b>排他锁定</b>权限项行（{@code SELECT ... FOR UPDATE}），供启停权限项
     * 串行化同一权限项的并发写入：取锁后读到的 status 即为最新值，"重复同状态"
     * 幂等判断不再有并发窗口，随后的无条件 UPDATE 也必然命中 1 行。
     *
     * <p>与角色模块的取锁惯例一致（{@code SysRoleMapper#selectByIdForUpdate}）：
     * 权限项启停/修改/替换的写锁在同一行上串行。调用方（权限项写服务）必须在
     * 本查询之前先共享锁操作人用户行（{@code OperatorRowLockMapper#selectOperatorForShare}），
     * 统一"用户 → 权限"锁序、避免与角色权限替换 / 用户角色分配的三事务锁环，
     * 见 {@code PermissionAdminServiceImpl} 的并发说明。</p>
     *
     * <p>权限项不存在时返回 {@code null} → 服务层 404。</p>
     */
    @Select("""
            SELECT id, permission_code, permission_name, permission_type, path, method,
                   status, created_at, updated_at
            FROM sys_permission
            WHERE id = #{id}
            FOR UPDATE
            """)
    SysPermission selectByIdForUpdate(@Param("id") Long id);

    /**
     * 按 ID 升序共享锁定待分配权限行（{@code SELECT ... FOR SHARE}），供角色权限
     * 全量替换在单事务内校验"存在 + 启用"（《跨模块服务接口 V1.0》第 2 节）。
     *
     * <p>共享锁持有到外层事务结束，保护"启用校验 → 关联写入"的窗口：期间权限
     * 启停的写锁会被这把共享锁挡住，避免刚校验通过的权限在提交前被停用。</p>
     *
     * <p>调用方约定：{@code permissionIds} 已去重并按 ID 升序排列，且<b>非空</b>——
     * 空集合不发出 IN 查询（契约明文），由服务层在调用前判断。返回列表自带
     * {@code status}，服务层据此区分"不存在 404 / 停用 409"。</p>
     */
    @Select("""
            <script>
            SELECT id, permission_code, permission_name, permission_type, path, method,
                   status, created_at, updated_at
            FROM sys_permission
            WHERE id IN
            <foreach collection="permissionIds" item="pid" open="(" separator="," close=")">#{pid}</foreach>
            ORDER BY id
            FOR SHARE
            </script>
            """)
    List<SysPermission> selectByIdsForShare(@Param("permissionIds") List<Long> permissionIds);
}
