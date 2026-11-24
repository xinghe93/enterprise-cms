package com.xinghe.cms.user.mapper;

import com.xinghe.cms.user.mapper.row.CurrentUserStatusRow;
import com.xinghe.cms.user.mapper.row.RoleBriefRow;
import com.xinghe.cms.user.mapper.row.UserCredentialsRow;
import com.xinghe.cms.user.mapper.row.UserProfileRow;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 认证与当前身份所需的只读查询。
 *
 * <p>sys_role 由 B 维护，此处仅做跨表只读 JOIN 回显，不写入他人负责的表；
 * 用户名、部门、角色均为参数绑定，列表先按角色 ID 升序，避免 N+1。</p>
 */
@Mapper
public interface UserAuthMapper {

    UserCredentialsRow findCredentialsByUsername(@Param("username") String username);

    CurrentUserStatusRow findStatusById(@Param("userId") Long userId);

    UserProfileRow findProfileById(@Param("userId") Long userId);

    List<RoleBriefRow> findRolesByUserId(@Param("userId") Long userId);
}
