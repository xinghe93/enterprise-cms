package com.xinghe.cms.permission.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xinghe.cms.permission.entity.SysRolePermission;
import org.apache.ibatis.annotations.Mapper;

/**
 * 角色权限关联表数据访问。复合主键表，全部操作基于 Wrapper 条件
 * （selectList / delete by wrapper），不使用 selectById。
 * 替换关联的行锁与批量 SQL 在 D9 按需以 XML 补充。
 */
@Mapper
public interface SysRolePermissionMapper extends BaseMapper<SysRolePermission> {
}
