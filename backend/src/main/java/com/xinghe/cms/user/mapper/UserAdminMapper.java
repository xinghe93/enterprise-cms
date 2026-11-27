package com.xinghe.cms.user.mapper;

import com.xinghe.cms.user.mapper.row.UserProfileRow;
import com.xinghe.cms.user.mapper.row.UserRoleRow;
import com.xinghe.cms.user.mapper.row.UserStatusSnapshot;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 员工管理（A 模块）的写侧查询。
 *
 * <p>只操作 A 负责的 {@code sys_user}；{@code sys_role} / {@code sys_user_role} 的
 * 校验通过 B 的共享服务，不在本 Mapper 里维护第二份关系。所有参数均使用 #{}
 * 绑定，不拼接 SQL。</p>
 */
@Mapper
public interface UserAdminMapper {

    /**
     * 事务内只锁 sys_user 行读取当前状态与 status_version，避免两个并发启停请求各自基于旧快照写库，
     * 也避免 JOIN 部门表连带锁住部门行造成调岗死锁（见 P2 审查意见）。
     * 登录链路的 {@code UserAuthMapper#findStatusById} 是普通快照读，不受此锁影响。
     *
     * @return 状态与撤销版本快照；用户不存在返回 {@code null}
     */
    UserStatusSnapshot findStatusForUpdate(@Param("userId") Long userId);

    /** 写事务内提前取得成功审计外键所需的操作人共享锁。 */
    Long findUserForShare(@Param("userId") Long userId);

    /** 更新状态后在同一事务内回读 status_version，作为本次状态变更的撤销版本。 */
    UserStatusSnapshot findStatusVersion(@Param("userId") Long userId);

    /**
     * 只改状态并原子递增 status_version（004 迁移脚本），返回受影响行数。
     * status_version 仅在状态真正变化时 +1，与资料更新无关，是会话撤销的独立单调版本。
     */
    int updateStatus(@Param("userId") Long userId, @Param("status") int status);

    /** 写事务内锁住员工行并读取完整资料（含部门名），用于 404 判定与「原部门不变」比较。 */
    UserProfileRow findProfileForUpdate(@Param("userId") Long userId);

    /** 创建后按唯一 username 回读，让 createdAt/updatedAt 完全取自数据库。 */
    UserProfileRow findProfileByUsername(@Param("username") String username);

    int insertUser(@Param("username") String username, @Param("passwordHash") String passwordHash,
            @Param("realName") String realName, @Param("departmentId") Long departmentId);

    /** 完整 PUT：只改姓名与部门，不改用户名、密码、状态、角色。 */
    int updateProfile(@Param("userId") Long userId, @Param("realName") String realName,
            @Param("departmentId") Long departmentId);

    UserProfileRow findProfileById(@Param("userId") Long userId);

    /** keywordPattern 由 Service 预先转义 % _ ! 并加上两端 %；null / 空串表示不筛选。 */
    long countUsers(@Param("keywordPattern") String keywordPattern,
            @Param("departmentIds") List<Long> departmentIds, @Param("status") Integer status);

    /** keywordPattern 由 Service 预先转义 % _ ! 并加上两端 %；null / 空串表示不筛选。 */
    List<UserProfileRow> findUsersPage(@Param("keywordPattern") String keywordPattern,
            @Param("departmentIds") List<Long> departmentIds, @Param("status") Integer status,
            @Param("offset") long offset, @Param("size") long size);

    /** 一次取回整页员工的角色，避免逐行 N+1；SQL 按 userId、roleId 升序。 */
    List<UserRoleRow> findRolesByUserIds(@Param("userIds") List<Long> userIds);

    List<Long> findRoleIdsByUserId(@Param("userId") Long userId);

    int deleteUserRole(@Param("userId") Long userId, @Param("roleId") Long roleId);

    int insertUserRoles(@Param("userId") Long userId, @Param("roleIds") List<Long> roleIds);

    /**
     * 本部门及全部后代 ID（含停用节点），用于员工分页的 departmentId 过滤。
     * ID 合法但不存在时返回空列表，契约要求此时是 200 空分页而不是 404。
     */
    List<Long> findDescendantDepartmentIds(@Param("departmentId") Long departmentId);
}
