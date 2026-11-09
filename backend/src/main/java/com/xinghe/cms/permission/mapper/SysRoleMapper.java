package com.xinghe.cms.permission.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xinghe.cms.permission.entity.SysRole;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 角色表数据访问。单表 CRUD 与分页由 MyBatis-Plus 提供，
 * 角色权限 JOIN 等多表 SQL 在 D3/D9 按需以 XML 补充。
 */
@Mapper
public interface SysRoleMapper extends BaseMapper<SysRole> {

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
}
