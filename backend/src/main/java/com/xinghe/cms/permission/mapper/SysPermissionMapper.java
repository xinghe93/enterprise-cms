package com.xinghe.cms.permission.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xinghe.cms.permission.entity.SysPermission;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 权限表数据访问。单表 CRUD 与分页由 MyBatis-Plus 提供，
 * 授权快照的三表 JOIN 在 D3 按需以 XML 补充。
 */
@Mapper
public interface SysPermissionMapper extends BaseMapper<SysPermission> {

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
