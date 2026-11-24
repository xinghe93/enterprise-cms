package com.xinghe.cms.permission.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.xinghe.cms.permission.entity.SysRole;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 角色表数据访问。单表 CRUD 由 MyBatis-Plus 提供，
 * 分页筛选与角色权限 JOIN 等组合 SQL 以 @Select script 显式声明。
 */
@Mapper
public interface SysRoleMapper extends BaseMapper<SysRole> {

    /**
     * 角色分页（管理端列表）：keyword 为转义后的字面关键词，对 role_code / role_name
     * 做包含匹配；status 为精确筛选。排序遵循契约 8.2 列表默认规则：
     * updated_at DESC, id DESC（id 兜底保证同时间戳下顺序稳定）。
     *
     * <p>调用方约定：keyword 已去首尾空白并完成 LIKE 通配符转义
     * （{@code !} 为转义字符），status 仅允许 0/1 或 null。</p>
     *
     * @param page    MyBatis-Plus 分页参数（current/size），同时接收总数
     * @param keyword 转义后的关键词，null 表示不筛选
     * @param status  状态筛选，null 表示不筛选
     */
    @Select("""
            <script>
            SELECT id, role_code, role_name, status, created_at, updated_at
            FROM sys_role
            <where>
            <if test="keyword != null">
            AND (role_code LIKE CONCAT('%', #{keyword}, '%') ESCAPE '!'
            OR role_name LIKE CONCAT('%', #{keyword}, '%') ESCAPE '!')
            </if>
            <if test="status != null">
            AND status = #{status}
            </if>
            </where>
            ORDER BY updated_at DESC, id DESC
            </script>
            """)
    Page<SysRole> selectRolePage(Page<SysRole> page, @Param("keyword") String keyword,
            @Param("status") Integer status);

    /**
     * 按 ID 升序共享锁定角色行（{@code SELECT ... FOR SHARE}），供跨模块角色分配校验。
     *
     * <p>共享锁持有到外层事务结束，保护“启用校验 → 关联写入”的窗口；
     * 与角色启停的写锁在同一库内串行（《跨模块服务接口 V1.0》第 2 节）。
     * 调用方必须先过滤空集合，本方法不接受空 IN 列表。</p>
     *
     * @param roleIds 已去重并升序排列的角色 ID，非空
     */
    @Select("""
            <script>
            SELECT id, role_code, role_name, status, created_at, updated_at
            FROM sys_role
            WHERE id IN
            <foreach collection="roleIds" item="roleId" open="(" separator="," close=")">#{roleId}</foreach>
            ORDER BY id
            FOR SHARE
            </script>
            """)
    List<SysRole> selectByIdsForShare(@Param("roleIds") List<Long> roleIds);

    /**
     * 按主键<b>排他锁定</b>角色行（{@code SELECT ... FOR UPDATE}），供角色权限全量替换
     * 串行化同一角色的并发写入（《跨模块服务接口 V1.0》第 2 节："B 的角色权限替换
     * 在单个事务中锁定目标角色……状态更新通过数据库写锁与这些校验串行"）。
     *
     * <p>与 {@link #selectByIdsForShare} 的共享锁不同：这里要防的是两个并发替换请求
     * 各自"读现有集合 → 删除 → 插入"相互覆盖（A 模块 {@code doReplaceRoles} 用同样的
     * FOR UPDATE 主表行锁串行化）。角色不存在时返回 {@code null} → 服务层 404。
     * 角色启停/删除的写锁也会与这把锁串行，避免校验窗口内角色被停用。</p>
     */
    @Select("""
            SELECT id, role_code, role_name, status, created_at, updated_at
            FROM sys_role
            WHERE id = #{id}
            FOR UPDATE
            """)
    SysRole selectByIdForUpdate(@Param("id") Long id);
}
