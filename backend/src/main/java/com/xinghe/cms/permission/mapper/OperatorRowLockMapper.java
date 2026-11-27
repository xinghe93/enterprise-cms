package com.xinghe.cms.permission.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 操作人用户行的锁定读（评审 P2：统一"用户 → 角色"取锁顺序，且只用共享锁）。
 *
 * <p><b>为什么需要它</b>：{@code sys_operation_log.user_id} 是指向
 * {@code sys_user(id)} 的外键，InnoDB 在写入审计日志时会对父表的操作人行
 * 加<b>共享</b>记录锁（S）。{@code RolePermissionServiceImpl#replacePermissions}
 * 原本先 {@code FOR UPDATE} 锁定目标角色行，直到事务末尾写审计时才隐式锁定
 * 操作人用户行，顺序是"角色 → 用户"；而 A 模块 {@code doReplaceRoles} 的顺序是
 * "目标用户 → 待分配角色"（《跨模块服务接口 V1.0》第 53 行）。当另一名管理员
 * 同时给本次权限修改的操作人分配同一个角色时，两个事务分别持有角色 / 用户
 * 行锁并等待对方，形成死锁，合法写请求回滚为 500。</p>
 *
 * <p><b>修复</b>：B 在事务开头、锁定角色之前，先锁定操作人用户行，把两个接口
 * 统一为"用户 → 角色"。此后审计写入的外键检查只是重复获取本事务已持有的锁，
 * 不再引入反向等待；业务变更与 SUCCESS 审计仍在同一事务内原子提交
 * （{@code beforeCommit} 写入，未改成异步或事后补写）。</p>
 *
 * <p>角色改名、启停和权限写入都先取操作人共享锁。共享锁与同一操作人的
 * 其他审计事务兼容，与 A 修改该员工时的排他锁互斥。发生争用时，两个事务
 * 先在用户行排队，再继续锁角色或权限行。</p>
 *
 * <p><b>模块边界</b>：本接口只做锁定读，不写 {@code sys_user} 数据，符合
 * 《跨模块服务接口 V1.0》第 45 行"A 维护用户、写入责任不改变"；取共享行锁
 * 不等于接管用户数据写入。操作人 ID 只来自 A 提供的
 * {@code security.CurrentUserProvider}，不接受请求参数；无认证上下文（内部
 * 调用、单元测试）时 Service 跳过本查询，与 {@code AuditLogAspect} 把
 * {@code user_id} 记为 null、外键不检查父表的行为保持一致。</p>
 */
@Mapper
public interface OperatorRowLockMapper {

    /**
     * 以<b>共享</b>行锁锁定操作人用户行，直到外层事务结束。
     *
     * <p>返回 {@code null} 只可能来自异常的脏数据（身份已由过滤器校验为
     * 存在且启用，外键也保证不会被并发删除）；Service 不因此改变业务结果，
     * 本方法的目的仅是固定加锁顺序，且只用 S 锁（见类 JavaDoc 的锁兼容分析）。</p>
     */
    @Select("SELECT id FROM sys_user WHERE id = #{userId} FOR SHARE")
    Long selectOperatorForShare(@Param("userId") Long userId);
}
