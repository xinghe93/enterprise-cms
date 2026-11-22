package com.xinghe.cms.permission.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

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
import com.xinghe.cms.security.CurrentUserProvider;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link RolePermissionServiceImpl} 写/读服务单测。
 *
 * <p>覆盖：回显（含停用、按 id 升序、404、空列表）、全量替换（404/409、
 * 空数组清空、幂等跳过写入与审计、去重升序锁顺序、空集合不发出 IN 查询、
 * 停用项不能借幂等保留、FOR UPDATE 锁角色行），以及三轮评审 P2 的死锁 / 审计
 * 修复：<b>差集替换</b>（空表首次分配不发 DELETE、部分重叠只删/插差集）、
 * <b>用户 → 角色</b>取锁顺序（本轮改为 {@code FOR SHARE} 共享锁，消除与角色
 * 编辑的新锁环）、<b>差集写入包进受审计代理</b>（写入失败仍补 FAILURE，由
 * {@code RolePermissionAuditTransactionTests} 覆盖）。SQL 与行锁本身由联调
 * 阶段集成验证；审计随事务提交由该测试类覆盖。</p>
 *
 * <p>审计锚点 {@code rolePermissionsChanged(Runnable)} 在本类中是 mock，用
 * {@code @BeforeEach} 的 Answer 执行传入的差集写入动作，模拟生产里受审计代理
 * 内部执行 {@code Runnable} 的行为，使既有 delete/insert 断言保持有效。</p>
 */
@ExtendWith(MockitoExtension.class)
class RolePermissionServiceImplTests {

    @Mock
    private SysRoleMapper sysRoleMapper;

    @Mock
    private SysPermissionMapper sysPermissionMapper;

    @Mock
    private SysRolePermissionMapper sysRolePermissionMapper;

    @Mock
    private OperatorRowLockMapper operatorRowLockMapper;

    @Mock
    private CurrentUserProvider currentUserProvider;

    @Mock
    private RoleWriteAuditMarker roleWriteAuditMarker;

    @InjectMocks
    private RolePermissionServiceImpl service;

    /**
     * 生产里审计代理（受 {@code @AuditLog} 环绕）会执行传入的差集写入
     * {@link Runnable}；单测中 marker 是 mock，需让其真正执行 Runnable，
     * 既有的 delete/insert 断言才成立。lenient：幂等 / 404 / 409 用例不调用
     * marker，避免 Mockito 严格桩把该 stub 判为未使用。
     */
    @BeforeEach
    void executeWriteActionInMarkerStub() {
        Mockito.lenient().doAnswer(invocation -> {
            Runnable writeAction = invocation.getArgument(0);
            writeAction.run();
            return null;
        }).when(roleWriteAuditMarker).rolePermissionsChanged(any(Runnable.class));
    }

    private static SysRole role(long id) {
        SysRole entity = new SysRole();
        entity.setId(id);
        entity.setRoleCode("EDITOR");
        entity.setRoleName("编辑");
        entity.setStatus(1);
        return entity;
    }

    private static SysPermission permission(long id, int status) {
        SysPermission entity = new SysPermission();
        entity.setId(id);
        entity.setPermissionCode("perm:" + id);
        entity.setPermissionName("权限" + id);
        entity.setPermissionType("API");
        entity.setPath("/api/admin/x/" + id);
        entity.setMethod(id % 2 == 0 ? "GET" : null);
        entity.setStatus(status);
        entity.setCreatedAt(LocalDateTime.of(2026, 11, 2, 16, 0));
        entity.setUpdatedAt(LocalDateTime.of(2026, 11, 18, 8, 50));
        return entity;
    }

    // ---------- GET 已分配权限回显 ----------

    /** 回显返回全部已分配权限（含停用），按 permissionId 升序，path/method 原样透传。 */
    @Test
    void getPermissionsReturnsAllIncludingDisabledSortedById() {
        when(sysRoleMapper.selectById(37L)).thenReturn(role(37L));
        // 故意乱序 + 含停用项（status=0），服务层需按 id 兜底排序且不过滤
        when(sysRolePermissionMapper.selectPermissionsByRoleId(37L))
                .thenReturn(List.of(permission(5L, 0), permission(1L, 1), permission(2L, 1)));

        List<PermissionView> views = service.getPermissions(37L);

        assertThat(views).extracting(PermissionView::id).containsExactly("1", "2", "5");
        assertThat(views).extracting(PermissionView::status).containsExactly(1, 1, 0);
        assertThat(views.get(0).permissionCode()).isEqualTo("perm:1");
        assertThat(views.get(1).method()).isEqualTo("GET");
        assertThat(views.get(2).method()).isNull(); // id=5 奇数，method 可空
        assertThat(views.get(0).createdAt()).isEqualTo(LocalDateTime.of(2026, 11, 2, 16, 0));
    }

    /** 未分配权限返回空列表（角色存在），不抛异常。 */
    @Test
    void getPermissionsReturnsEmptyWhenNoneAssigned() {
        when(sysRoleMapper.selectById(41L)).thenReturn(role(41L));
        when(sysRolePermissionMapper.selectPermissionsByRoleId(41L)).thenReturn(List.of());

        assertThat(service.getPermissions(41L)).isEmpty();
    }

    /** mapper 返回 null 也按空列表处理（防御性，不 NPE）。 */
    @Test
    void getPermissionsTreatsNullMapperResultAsEmpty() {
        when(sysRoleMapper.selectById(41L)).thenReturn(role(41L));
        when(sysRolePermissionMapper.selectPermissionsByRoleId(41L)).thenReturn(null);

        assertThat(service.getPermissions(41L)).isEmpty();
    }

    /** 角色不存在返回 404，且不查关联表。 */
    @Test
    void getPermissionsThrows404WhenRoleMissing() {
        when(sysRoleMapper.selectById(999L)).thenReturn(null);

        assertThatThrownBy(() -> service.getPermissions(999L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getResultCode())
                .isEqualTo(ResultCode.NOT_FOUND);

        verifyNoInteractions(sysRolePermissionMapper);
    }

    // ---------- PUT 全量替换 ----------

    /** 正常替换：FOR UPDATE 锁角色、共享锁校验、按差集删除 + 插入、记录变更审计。 */
    @Test
    void replacePermissionsDeletesInsertsAndRecordsAudit() {
        when(sysRoleMapper.selectByIdForUpdate(37L)).thenReturn(role(37L));
        when(sysPermissionMapper.selectByIdsForShare(List.of(1L, 2L)))
                .thenReturn(List.of(permission(1L, 1), permission(2L, 1)));
        when(sysRolePermissionMapper.selectPermissionIdsByRoleId(37L)).thenReturn(List.of(3L));

        service.replacePermissions(37L, new RolePermissionsUpdateRequest(List.of(1L, 2L)));

        verify(sysRoleMapper).selectByIdForUpdate(37L);
        verify(sysRolePermissionMapper).deletePermissions(37L, List.of(3L));
        ArgumentCaptor<List<Long>> captor = ArgumentCaptor.forClass(List.class);
        verify(sysRolePermissionMapper).insertPermissions(org.mockito.ArgumentMatchers.eq(37L),
                captor.capture());
        assertThat(captor.getValue()).containsExactly(1L, 2L);
        verify(roleWriteAuditMarker).rolePermissionsChanged(any(Runnable.class));
    }

    /** 空数组清空：只删差集移除项、不插入、不发出 IN 查询，仍记录变更审计。 */
    @Test
    void replaceWithEmptyArrayClearsAllWithoutLockQuery() {
        when(sysRoleMapper.selectByIdForUpdate(37L)).thenReturn(role(37L));
        when(sysRolePermissionMapper.selectPermissionIdsByRoleId(37L)).thenReturn(List.of(1L, 2L));

        service.replacePermissions(37L, new RolePermissionsUpdateRequest(List.of()));

        verify(sysRolePermissionMapper).deletePermissions(37L, List.of(1L, 2L));
        verify(sysRolePermissionMapper, never()).insertPermissions(any(), anyList());
        verifyNoInteractions(sysPermissionMapper); // 空集合不发出 IN 查询（契约明文）
        verify(roleWriteAuditMarker).rolePermissionsChanged(any(Runnable.class));
    }

    /** 幂等：相同集合（请求乱序）不写库、不记审计（评审 P2 同源结论）。 */
    @Test
    void idempotentSameSetSkipsWritesAndAudit() {
        when(sysRoleMapper.selectByIdForUpdate(37L)).thenReturn(role(37L));
        when(sysPermissionMapper.selectByIdsForShare(List.of(1L, 2L)))
                .thenReturn(List.of(permission(1L, 1), permission(2L, 1)));
        when(sysRolePermissionMapper.selectPermissionIdsByRoleId(37L)).thenReturn(List.of(1L, 2L));

        service.replacePermissions(37L, new RolePermissionsUpdateRequest(List.of(2L, 1L)));

        verify(sysRolePermissionMapper, never()).deletePermissions(any(), anyList());
        verify(sysRolePermissionMapper, never()).insertPermissions(any(), anyList());
        verify(roleWriteAuditMarker, never()).rolePermissionsChanged(any(Runnable.class));
    }

    /** 幂等空集合（当前也为空）不写库、不记审计。 */
    @Test
    void idempotentEmptySetSkipsWritesAndAudit() {
        when(sysRoleMapper.selectByIdForUpdate(37L)).thenReturn(role(37L));
        when(sysRolePermissionMapper.selectPermissionIdsByRoleId(37L)).thenReturn(List.of());

        service.replacePermissions(37L, new RolePermissionsUpdateRequest(List.of()));

        verify(sysRolePermissionMapper, never()).deletePermissions(any(), anyList());
        verify(roleWriteAuditMarker, never()).rolePermissionsChanged(any(Runnable.class));
    }

    /** 角色不存在 404，且不做任何后续校验/写入/审计。 */
    @Test
    void replacePermissionsThrows404WhenRoleMissing() {
        when(sysRoleMapper.selectByIdForUpdate(999L)).thenReturn(null);

        assertThatThrownBy(() -> service.replacePermissions(999L,
                new RolePermissionsUpdateRequest(List.of(1L))))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getResultCode())
                .isEqualTo(ResultCode.NOT_FOUND);

        verifyNoInteractions(sysPermissionMapper, sysRolePermissionMapper, roleWriteAuditMarker);
    }

    /** 待分配权限不存在 404（锁定结果数量不足），整体回滚前不写库、不记审计。 */
    @Test
    void replacePermissionsThrows404WhenPermissionMissing() {
        when(sysRoleMapper.selectByIdForUpdate(37L)).thenReturn(role(37L));
        when(sysPermissionMapper.selectByIdsForShare(List.of(1L, 2L)))
                .thenReturn(List.of(permission(1L, 1))); // 只查到 1 个

        assertThatThrownBy(() -> service.replacePermissions(37L,
                new RolePermissionsUpdateRequest(List.of(1L, 2L))))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getResultCode())
                .isEqualTo(ResultCode.NOT_FOUND);

        verify(sysRolePermissionMapper, never()).deletePermissions(any(), anyList());
        verify(roleWriteAuditMarker, never()).rolePermissionsChanged(any(Runnable.class));
    }

    /** 待分配权限停用 409——即便集合与当前相同（幂等）也要先校验，不能借幂等保留。 */
    @Test
    void replacePermissionsThrows409WhenPermissionDisabledEvenIfSetUnchanged() {
        when(sysRoleMapper.selectByIdForUpdate(37L)).thenReturn(role(37L));
        when(sysPermissionMapper.selectByIdsForShare(List.of(1L, 2L)))
                .thenReturn(List.of(permission(1L, 1), permission(2L, 0))); // 2 已停用

        assertThatThrownBy(() -> service.replacePermissions(37L,
                new RolePermissionsUpdateRequest(List.of(1L, 2L))))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getResultCode())
                .isEqualTo(ResultCode.CONFLICT);

        // 关键：校验先于幂等比较——即便本用例不 stub 现有关联（默认 null→空），
        // 也绝不能走到"集合相同就返回"的分支
        verify(sysRolePermissionMapper, never()).deletePermissions(any(), anyList());
        verify(roleWriteAuditMarker, never()).rolePermissionsChanged(any(Runnable.class));
    }

    /** status 为 null 也按停用处理（防御性，不让脏数据绕过启用校验）。 */
    @Test
    void replacePermissionsTreatsNullStatusAsDisabled() {
        when(sysRoleMapper.selectByIdForUpdate(37L)).thenReturn(role(37L));
        SysPermission nullStatus = permission(1L, 1);
        nullStatus.setStatus(null);
        when(sysPermissionMapper.selectByIdsForShare(List.of(1L))).thenReturn(List.of(nullStatus));

        assertThatThrownBy(() -> service.replacePermissions(37L,
                new RolePermissionsUpdateRequest(List.of(1L))))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getResultCode())
                .isEqualTo(ResultCode.CONFLICT);
    }

    /** 请求含重复/乱序 ID 时服务层防御性去重并升序，固定加锁与插入顺序。 */
    @Test
    void replacePermissionsNormalizesDistinctAndSorted() {
        when(sysRoleMapper.selectByIdForUpdate(37L)).thenReturn(role(37L));
        when(sysPermissionMapper.selectByIdsForShare(List.of(1L, 2L, 3L)))
                .thenReturn(List.of(permission(1L, 1), permission(2L, 1), permission(3L, 1)));
        when(sysRolePermissionMapper.selectPermissionIdsByRoleId(37L)).thenReturn(List.of(9L));

        service.replacePermissions(37L,
                new RolePermissionsUpdateRequest(List.of(3L, 1L, 2L, 2L)));

        // 共享锁与批量插入都收到去重升序后的 [1,2,3]
        verify(sysPermissionMapper).selectByIdsForShare(List.of(1L, 2L, 3L));
        verify(sysRolePermissionMapper).deletePermissions(37L, List.of(9L));
        ArgumentCaptor<List<Long>> captor = ArgumentCaptor.forClass(List.class);
        verify(sysRolePermissionMapper).insertPermissions(org.mockito.ArgumentMatchers.eq(37L),
                captor.capture());
        assertThat(captor.getValue()).containsExactly(1L, 2L, 3L);
    }

    /** 现有关联为 null（mapper 防御）按空集合：只插新增项，不发 DELETE。 */
    @Test
    void replacePermissionsTreatsNullCurrentAsEmpty() {
        when(sysRoleMapper.selectByIdForUpdate(37L)).thenReturn(role(37L));
        when(sysPermissionMapper.selectByIdsForShare(List.of(1L)))
                .thenReturn(List.of(permission(1L, 1)));
        when(sysRolePermissionMapper.selectPermissionIdsByRoleId(37L)).thenReturn(null);

        service.replacePermissions(37L, new RolePermissionsUpdateRequest(List.of(1L)));

        verify(sysRolePermissionMapper, never()).deletePermissions(any(), anyList());
        verify(sysRolePermissionMapper).insertPermissions(37L, List.of(1L));
        verify(roleWriteAuditMarker).rolePermissionsChanged(any(Runnable.class));
    }

    // ---------- 评审 P2：两处并发死锁修复 ----------

    /**
     * 评审 P2 ①：关联表为空时的首次分配完全不发 DELETE。
     *
     * <p>原实现"先删后插"会对空表执行无匹配的 {@code DELETE WHERE role_id}，
     * 在 REPEATABLE READ 下两个不同角色的事务各锁同一个索引间隙，随后 INSERT
     * 互相等待而死锁。差集替换下删除差集为空 → 不发 DELETE → 只剩 INSERT。</p>
     */
    @Test
    void firstAssignmentOnEmptyTableSkipsDeleteEntirely() {
        when(sysRoleMapper.selectByIdForUpdate(37L)).thenReturn(role(37L));
        when(sysPermissionMapper.selectByIdsForShare(List.of(1L, 2L)))
                .thenReturn(List.of(permission(1L, 1), permission(2L, 1)));
        when(sysRolePermissionMapper.selectPermissionIdsByRoleId(37L)).thenReturn(List.of());

        service.replacePermissions(37L, new RolePermissionsUpdateRequest(List.of(1L, 2L)));

        verify(sysRolePermissionMapper, never()).deletePermissions(any(), anyList());
        verify(sysRolePermissionMapper).insertPermissions(37L, List.of(1L, 2L));
        verify(roleWriteAuditMarker).rolePermissionsChanged(any(Runnable.class));
    }

    /** 评审 P2 ①：部分重叠时只删移除项 [1]、只插新增项 [4]，交集 [2,3] 保持不动。 */
    @Test
    void partialOverlapOnlyAppliesDiff() {
        when(sysRoleMapper.selectByIdForUpdate(37L)).thenReturn(role(37L));
        when(sysPermissionMapper.selectByIdsForShare(List.of(2L, 3L, 4L)))
                .thenReturn(List.of(permission(2L, 1), permission(3L, 1), permission(4L, 1)));
        when(sysRolePermissionMapper.selectPermissionIdsByRoleId(37L))
                .thenReturn(List.of(1L, 2L, 3L));

        service.replacePermissions(37L, new RolePermissionsUpdateRequest(List.of(4L, 3L, 2L)));

        verify(sysRolePermissionMapper).deletePermissions(37L, List.of(1L));
        verify(sysRolePermissionMapper).insertPermissions(37L, List.of(4L));
        verify(roleWriteAuditMarker).rolePermissionsChanged(any(Runnable.class));
    }

    /**
     * 评审 P2 ②：先锁操作人用户行、再锁角色行，与 A 模块用户角色分配同序。
     *
     * <p>审计日志 {@code user_id} 外键会在事务末尾隐式锁操作人用户行；提前取锁
     * 后不再出现"角色 → 用户"与"用户 → 角色"的锁环。本轮进一步把该锁从
     * {@code FOR UPDATE}（X）改为 {@code FOR SHARE}（S）：与角色编辑 / 本事务
     * 审计外键检查的 S 锁兼容、与 A 模块用户排他锁互斥，消除新锁环的同时保持
     * "用户 → 角色"串行化（锁兼容矩阵见 {@code OperatorRowLockMapper} 类 JavaDoc，
     * 真实锁行为由 MySQL 双会话脚本验证）。</p>
     */
    @Test
    void lockOrderIsUserBeforeRoleWithSharedOperatorLock() {
        when(currentUserProvider.requireCurrentUser())
                .thenReturn(new CurrentUser(9L, "admin", null));
        when(sysRoleMapper.selectByIdForUpdate(37L)).thenReturn(role(37L));
        when(sysPermissionMapper.selectByIdsForShare(List.of(1L)))
                .thenReturn(List.of(permission(1L, 1)));
        when(sysRolePermissionMapper.selectPermissionIdsByRoleId(37L)).thenReturn(List.of());

        service.replacePermissions(37L, new RolePermissionsUpdateRequest(List.of(1L)));

        InOrder inOrder = Mockito.inOrder(operatorRowLockMapper, sysRoleMapper);
        inOrder.verify(operatorRowLockMapper).selectOperatorForShare(9L);
        inOrder.verify(sysRoleMapper).selectByIdForUpdate(37L);
    }

    /**
     * 本轮评审 P2 ②：差集写入包进受审计代理的 {@link Runnable}，写入动作在代理
     * 内部执行（删除先于插入），而非在锚点调用之前；Mapper 抛错时异常能冒泡到
     * 已注册的切面，事务回滚后补记 FAILURE（真实代理链见
     * {@code RolePermissionAuditTransactionTests}）。
     */
    @Test
    void diffWritesAreExecutedInsideAuditProxyRunnable() {
        when(sysRoleMapper.selectByIdForUpdate(37L)).thenReturn(role(37L));
        when(sysPermissionMapper.selectByIdsForShare(List.of(2L, 3L)))
                .thenReturn(List.of(permission(2L, 1), permission(3L, 1)));
        when(sysRolePermissionMapper.selectPermissionIdsByRoleId(37L))
                .thenReturn(List.of(1L, 2L));

        service.replacePermissions(37L, new RolePermissionsUpdateRequest(List.of(3L, 2L)));

        // 传入审计代理的 Runnable 确实执行了差集，且删在插之前
        InOrder writeOrder = Mockito.inOrder(sysRolePermissionMapper);
        writeOrder.verify(sysRolePermissionMapper).deletePermissions(37L, List.of(1L));
        writeOrder.verify(sysRolePermissionMapper).insertPermissions(37L, List.of(3L));
        verify(roleWriteAuditMarker).rolePermissionsChanged(any(Runnable.class));
    }

    /** 无认证上下文（内部调用/单测）跳过用户行锁，与 AuditLogAspect 记 null user_id 一致。 */
    @Test
    void unauthenticatedContextSkipsOperatorLock() {
        when(currentUserProvider.requireCurrentUser())
                .thenThrow(new BusinessException(ResultCode.UNAUTHORIZED));
        when(sysRoleMapper.selectByIdForUpdate(37L)).thenReturn(role(37L));
        when(sysRolePermissionMapper.selectPermissionIdsByRoleId(37L)).thenReturn(List.of());

        service.replacePermissions(37L, new RolePermissionsUpdateRequest(List.of()));

        verifyNoInteractions(operatorRowLockMapper);
        verify(sysRoleMapper).selectByIdForUpdate(37L);
        verify(roleWriteAuditMarker, never()).rolePermissionsChanged(any(Runnable.class));
    }
}
