package com.xinghe.cms.permission.service.impl;

import com.xinghe.cms.common.contract.CurrentUser;
import com.xinghe.cms.common.exception.BusinessException;
import com.xinghe.cms.common.response.ResultCode;
import com.xinghe.cms.permission.entity.SysPermission;
import com.xinghe.cms.permission.entity.SysRole;
import com.xinghe.cms.permission.mapper.OperatorRowLockMapper;
import com.xinghe.cms.permission.mapper.SysPermissionMapper;
import com.xinghe.cms.permission.mapper.SysRoleMapper;
import com.xinghe.cms.permission.mapper.SysRolePermissionMapper;
import com.xinghe.cms.permission.request.RolePermissionsUpdateRequest;
import com.xinghe.cms.permission.response.PermissionView;
import com.xinghe.cms.permission.service.RolePermissionService;
import com.xinghe.cms.security.CurrentUserProvider;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 角色权限关联服务实现，对齐 OpenAPI
 * {@code GET / PUT /api/admin/roles/{id}/permissions}。
 *
 * <p><b>取锁顺序（评审 P2）</b>：事务内先 {@code FOR UPDATE} 锁定操作人用户行
 * （{@link OperatorRowLockMapper}），再 {@code FOR UPDATE} 锁定目标角色行，
 * 之后按 ID 升序 {@code FOR SHARE} 共享锁定待分配权限行。与 A 模块
 * {@code doReplaceRoles} 的"目标用户 → 待分配角色"顺序一致（《跨模块服务接口
 * V1.0》第 53 行）。{@code sys_operation_log.user_id} 外键在写审计时会锁定
 * 操作人用户行；若先锁角色、事务末尾才由外键检查隐式锁用户，与 A 的用户角色
 * 分配并发时会形成"角色 ↔ 用户"的锁环，合法写请求被回滚为 500。提前锁用户
 * 后，审计写入只是重复获取本事务已持有的行锁，不再反向等待；业务变更与
 * SUCCESS 审计仍在同一事务内原子提交。</p>
 *
 * <p><b>全量替换顺序</b>（《跨模块服务接口 V1.0》第 2 节 + 协作约定 8.1）：
 * 单事务内：① 锁操作人用户行；② {@code FOR UPDATE} 锁定目标角色行（404 判定
 * + 串行化同一角色的并发替换，也保证后续差集基于同一最新快照）；③ 请求集合
 * 去重并按 ID 升序，非空时 {@code FOR SHARE} 按升序共享锁定待分配权限行、
 * 校验存在（404）与启用（409）；④ 读当前关联并计算差集；⑤ 只删差集移除项、
 * 只插差集新增项；⑥ 经审计锚点记录变更。空集合不发出 IN 查询（契约明文）。</p>
 *
 * <p><b>差集替换（评审 P2）</b>：不再"先删后插整表清空"。关联表为空时两个
 * 不同角色的事务各自执行一次无匹配的 {@code DELETE WHERE role_id = ?} 会锁住
 * 同一个索引间隙（REPEATABLE READ 下 gap lock 互相兼容），随后两次 INSERT 的
 * 插入意向锁互相等待 → 死锁。改为按完整复合主键删除 {@code current - target}、
 * 只插入 {@code target - current}：空表首次分配的删除差集为空，完全不发 DELETE，
 * 两个事务只剩 INSERT，插入意向锁不冲突。差集均为空即幂等（契约 8.1）。</p>
 *
 * <p>"既有停用权限不能保留在本次请求中"（契约明文）：即便请求集合与当前关联
 * 完全相同，也要先跑一遍启用校验，否则已停用的权限项会借幂等语义继续保留。
 * 因此<b>校验必须先于幂等比较（差集计算）</b>，与 A 模块 {@code doReplaceRoles}
 * 一致。</p>
 *
 * <p><b>审计</b>（评审 P2 同源结论）：{@code AuditLogAspect} 要求 {@code @AuditLog}
 * 标在事务边界内、经 Spring 代理调用的方法上，且没有条件跳过机制。存在
 * "相同集合幂等 200、不写库"的无状态变更路径，若把注解直接标在
 * {@code replacePermissions} 上，幂等路径也会在提交前写 SUCCESS——审计写入
 * 失败会拖垮本应 200 的幂等请求。故与 {@link RoleWriteAuditMarker#roleStatusChanged}
 * 相同，注解标在 {@link RoleWriteAuditMarker#rolePermissionsChanged} 锚点上，
 * 仅在差集确实非空（删除 / 插入已执行）后经代理调用：success 审计随替换事务
 * 一起提交，审计写入失败则整个替换事务回滚；幂等路径不产生审计记录。
 * 404/409 校验失败发生在锚点之前，切面未进入，不产生 FAILURE 审计。</p>
 *
 * <p><b>缓存</b>：契约要求"提交后失效权限缓存，回滚保留原权限"。首期不引入
 * 跨请求权限缓存——{@code AuthorizationQueryService} 每请求从数据库实时计算
 * 授权快照（见《跨模块服务接口》第 2 节、协作约定 8.1），因此提交后新请求
 * 自然读到新规则、回滚后旧关联仍在，无需（也无对象可）失效缓存。若将来引入
 * 缓存，必须在事务提交后清理，不得提交前刷新。</p>
 */
@Service
public class RolePermissionServiceImpl implements RolePermissionService {

    /** {@code sys_permission.status} 的启用值（0 停用、1 启用）。 */
    private static final int STATUS_ENABLED = 1;

    private final SysRoleMapper sysRoleMapper;
    private final SysPermissionMapper sysPermissionMapper;
    private final SysRolePermissionMapper sysRolePermissionMapper;

    /** 评审 P2：事务开头先锁定操作人用户行，统一"用户 → 角色"取锁顺序。 */
    private final OperatorRowLockMapper operatorRowLockMapper;

    /** A 提供的身份入口：操作人 ID 只取已验证身份，不接受请求参数。 */
    private final CurrentUserProvider currentUserProvider;

    /** 变更审计锚点（评审 P2）：仅在权限集合实际变化时经代理调用。 */
    private final RoleWriteAuditMarker roleWriteAuditMarker;

    public RolePermissionServiceImpl(SysRoleMapper sysRoleMapper,
            SysPermissionMapper sysPermissionMapper,
            SysRolePermissionMapper sysRolePermissionMapper,
            OperatorRowLockMapper operatorRowLockMapper,
            CurrentUserProvider currentUserProvider,
            RoleWriteAuditMarker roleWriteAuditMarker) {
        this.sysRoleMapper = sysRoleMapper;
        this.sysPermissionMapper = sysPermissionMapper;
        this.sysRolePermissionMapper = sysRolePermissionMapper;
        this.operatorRowLockMapper = operatorRowLockMapper;
        this.currentUserProvider = currentUserProvider;
        this.roleWriteAuditMarker = roleWriteAuditMarker;
    }

    @Override
    @Transactional(readOnly = true)
    public List<PermissionView> getPermissions(long roleId) {
        // 角色不存在 404（只读查询不加锁）；回显返回全部已分配权限（含停用）
        SysRole role = sysRoleMapper.selectById(roleId);
        if (role == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "角色不存在");
        }
        // SQL 已按 permission_id 升序、含停用；再按 id 兜底排一次，保证契约不依赖
        // 驱动/执行计划的行序（与 D 模块 findByArticleIds 二次排序同源）
        List<SysPermission> permissions = sysRolePermissionMapper.selectPermissionsByRoleId(roleId);
        if (permissions == null || permissions.isEmpty()) {
            return List.of();
        }
        return permissions.stream()
                .sorted(Comparator.comparing(SysPermission::getId))
                .map(PermissionView::from)
                .toList();
    }

    @Override
    @Transactional
    public void replacePermissions(long roleId, RolePermissionsUpdateRequest request) {
        // 0) 评审 P2：先锁操作人用户行，再锁角色行，与 A 模块用户角色分配同序。
        //    sys_operation_log.user_id 外键在写审计时会检查父表并加共享记录锁，
        //    若不提前取该锁，就会与"用户 → 角色"的 A 事务形成锁环。
        lockOperatorFirst();

        // 1) FOR UPDATE 锁定目标角色行：判 404，并让同一角色的并发替换串行化
        //    （A 模块 doReplaceRoles 同款主表行锁；角色启停写锁也与之串行）。
        if (sysRoleMapper.selectByIdForUpdate(roleId) == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "角色不存在");
        }

        // 2) 规范化：DTO 已去重，服务层防御性去重 + 按 ID 升序（固定加锁顺序，
        //    避免与反向顺序的并发请求互锁；与 RoleDirectoryServiceImpl 同源）。
        List<Long> target = request.permissionIds().stream()
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .toList();

        // 3) 非空时按 ID 升序共享锁定待分配权限行，校验存在（404）与启用（409）。
        //    空集合不发出 IN 查询（契约明文）。即便集合与现有关联相同也要跑这一步：
        //    "既有停用权限不能保留在本次替换请求中"，不能借幂等跳过校验。
        if (!target.isEmpty()) {
            List<SysPermission> locked = sysPermissionMapper.selectByIdsForShare(target);
            if (locked == null || locked.size() != target.size()) {
                throw new BusinessException(ResultCode.NOT_FOUND, "权限不存在");
            }
            boolean hasDisabled = locked.stream()
                    .anyMatch(permission -> permission.getStatus() == null
                            || permission.getStatus() != STATUS_ENABLED);
            if (hasDisabled) {
                throw new BusinessException(ResultCode.CONFLICT, "权限已停用，无法分配");
            }
        }

        // 4) 差集计算（评审 P2）：current - target 删除、target - current 插入。
        //    selectPermissionIdsByRoleId 是普通一致性读，空表不加锁；两个差集
        //    均为空即幂等（协作约定 8.1：全量替换相同有效集合返回 200，不重复插关联）。
        List<Long> current = sysRolePermissionMapper.selectPermissionIdsByRoleId(roleId);
        List<Long> currentIds = current == null ? List.of() : current;
        Set<Long> targetSet = new HashSet<>(target);
        Set<Long> currentSet = new HashSet<>(currentIds);
        List<Long> toRemove = currentIds.stream()
                .filter(id -> !targetSet.contains(id))
                .distinct()
                .toList(); // SQL 已按 permission_id 升序
        List<Long> toAdd = target.stream()
                .filter(id -> !currentSet.contains(id))
                .toList(); // target 已按 ID 升序
        if (toRemove.isEmpty() && toAdd.isEmpty()) {
            return; // 无状态变更：不写库、不调用审计锚点（评审 P2）
        }

        // 5) 实际变更：只删差集移除项、只插差集新增项，整体在本事务内，
        //    任一失败回滚保留原关联。空删除差集（空表首次分配）不发 DELETE，
        //    避免 REPEATABLE READ 下无匹配 DELETE 的索引间隙锁死锁。
        if (!toRemove.isEmpty()) {
            sysRolePermissionMapper.deletePermissions(roleId, toRemove);
        }
        if (!toAdd.isEmpty()) {
            sysRolePermissionMapper.insertPermissions(roleId, toAdd);
        }

        // 6) 仅集合实际变化时记审计：经 Spring 代理进入锚点，切面在事务内注册
        //    同步器，beforeCommit 随替换事务写 role.assign-permission / SUCCESS。
        roleWriteAuditMarker.rolePermissionsChanged();
    }

    /**
     * 评审 P2：在锁定角色行之前先锁定操作人用户行，统一"用户 → 角色"顺序。
     *
     * <p>操作人只取 A 的 {@code CurrentUserProvider}（已验证身份），不接受请求
     * 参数。无认证上下文（内部调用、单元测试）时 {@code requireCurrentUser()}
     * 抛 UNAUTHORIZED 或返回空身份，此处跳过——与
     * {@code AuditLogAspect#resolveUserId} 一致：此时审计 {@code user_id} 为
     * {@code null}、外键不检查父表，本来就不会产生隐式加锁，跳过既不破坏顺序
     * 也不隐藏已认证请求的锁。</p>
     */
    private void lockOperatorFirst() {
        try {
            CurrentUser current = currentUserProvider.requireCurrentUser();
            if (current != null && current.userId() != null) {
                operatorRowLockMapper.selectOperatorForUpdate(current.userId());
            }
        } catch (BusinessException exception) {
            // 与 AuditLogAspect 一致：无认证上下文不伪造身份，也不加这把锁。
        }
    }
}
