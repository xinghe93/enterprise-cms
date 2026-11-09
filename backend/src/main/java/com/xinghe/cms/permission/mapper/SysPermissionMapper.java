package com.xinghe.cms.permission.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xinghe.cms.permission.entity.SysPermission;
import org.apache.ibatis.annotations.Mapper;

/**
 * 权限表数据访问。单表 CRUD 与分页由 MyBatis-Plus 提供，
 * 授权快照的三表 JOIN 在 D3 按需以 XML 补充。
 */
@Mapper
public interface SysPermissionMapper extends BaseMapper<SysPermission> {
}
