package com.xinghe.cms.permission.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xinghe.cms.permission.entity.SysRole;
import org.apache.ibatis.annotations.Mapper;

/**
 * 角色表数据访问。单表 CRUD 与分页由 MyBatis-Plus 提供，
 * 角色权限 JOIN 等多表 SQL 在 D3/D9 按需以 XML 补充。
 */
@Mapper
public interface SysRoleMapper extends BaseMapper<SysRole> {
}
