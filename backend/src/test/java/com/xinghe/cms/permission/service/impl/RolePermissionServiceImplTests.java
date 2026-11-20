package com.xinghe.cms.permission.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.xinghe.cms.common.exception.BusinessException;
import com.xinghe.cms.common.response.ResultCode;
import com.xinghe.cms.permission.entity.SysPermission;
import com.xinghe.cms.permission.entity.SysRole;
import com.xinghe.cms.permission.mapper.SysPermissionMapper;
import com.xinghe.cms.permission.mapper.SysRoleMapper;
import com.xinghe.cms.permission.mapper.SysRolePermissionMapper;
import com.xinghe.cms.permission.request.RolePermissionsUpdateRequest;
import com.xinghe.cms.permission.response.PermissionView;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link RolePermissionServiceImpl} 写/读服务单测。
 *
 * <p>覆盖：回显（含停用、按 id 升序、404、空列表）、全量替换（404/409、
 * 空数组清空、幂等跳过写入与审计、去重升序锁顺序、空集合不发出 IN 查询、
 * 停用项不能借幂等保留、FOR UPDATE 锁角色行）。SQL 与行锁本身由联调阶段
 * 集成验证；审计随事务提交由 {@code RolePermissionAuditTransactionTests} 覆盖。</p>
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
    private RoleWriteAuditMarker roleWriteAuditMarker;

    @InjectMocks
    private RolePermissionServiceImpl service;

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

    /** 正常替换：FOR UPDATE 锁角色、共享锁校验、删除 + 批量插入、记录变更审计。 */
    @Test
    void replacePermissionsDeletesInsertsAndRecordsAudit() {
        when(sysRoleMapper.selectByIdForUpdate(37L)).thenReturn(role(37L));
        when(sysPermissionMapper.selectByIdsForShare(List.of(1L, 2L)))
                .thenReturn(List.of(permission(1L, 1), permission(2L, 1)));
        when(sysRolePermissionMapper.selectPermissionIdsByRoleId(37L)).thenReturn(List.of(3L));

        service.replacePermissions(37L, new RolePermissionsUpdateRequest(List.of(1L, 2L)));

        verify(sysRoleMapper).selectByIdForUpdate(37L);
        verify(sysRolePermissionMapper).deleteByRoleId(37L);
        ArgumentCaptor<List<Long>> captor = ArgumentCaptor.forClass(List.class);
        verify(sysRolePermissionMapper).insertPermissions(org.mockito.ArgumentMatchers.eq(37L),
                captor.capture());
        assertThat(captor.getValue()).containsExactly(1L, 2L);
        verify(roleWriteAuditMarker).rolePermissionsChanged();
    }

    /** 空数组清空：只删除、不插入、不发出 IN 查询，仍记录变更审计（集合确实变了）。 */
    @Test
    void replaceWithEmptyArrayClearsAllWithoutLockQuery() {
        when(sysRoleMapper.selectByIdForUpdate(37L)).thenReturn(role(37L));
        when(sysRolePermissionMapper.selectPermissionIdsByRoleId(37L)).thenReturn(List.of(1L, 2L));

        service.replacePermissions(37L, new RolePermissionsUpdateRequest(List.of()));

        verify(sysRolePermissionMapper).deleteByRoleId(37L);
        verify(sysRolePermissionMapper, never()).insertPermissions(any(), anyList());
        verifyNoInteractions(sysPermissionMapper); // 空集合不发出 IN 查询（契约明文）
        verify(roleWriteAuditMarker).rolePermissionsChanged();
    }

    /** 幂等：相同集合不写库、不记审计（评审 P2 同源结论）。 */
    @Test
    void idempotentSameSetSkipsWritesAndAudit() {
        when(sysRoleMapper.selectByIdForUpdate(37L)).thenReturn(role(37L));
        when(sysPermissionMapper.selectByIdsForShare(List.of(1L, 2L)))
                .thenReturn(List.of(permission(1L, 1), permission(2L, 1)));
        when(sysRolePermissionMapper.selectPermissionIdsByRoleId(37L)).thenReturn(List.of(1L, 2L));

        service.replacePermissions(37L, new RolePermissionsUpdateRequest(List.of(2L, 1L)));

        verify(sysRolePermissionMapper, never()).deleteByRoleId(any());
        verify(sysRolePermissionMapper, never()).insertPermissions(any(), anyList());
        verify(roleWriteAuditMarker, never()).rolePermissionsChanged();
    }

    /** 幂等空集合（当前也为空）不写库、不记审计。 */
    @Test
    void idempotentEmptySetSkipsWritesAndAudit() {
        when(sysRoleMapper.selectByIdForUpdate(37L)).thenReturn(role(37L));
        when(sysRolePermissionMapper.selectPermissionIdsByRoleId(37L)).thenReturn(List.of());

        service.replacePermissions(37L, new RolePermissionsUpdateRequest(List.of()));

        verify(sysRolePermissionMapper, never()).deleteByRoleId(any());
        verify(roleWriteAuditMarker, never()).rolePermissionsChanged();
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

        verify(sysRolePermissionMapper, never()).deleteByRoleId(any());
        verify(roleWriteAuditMarker, never()).rolePermissionsChanged();
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
        verify(sysRolePermissionMapper, never()).deleteByRoleId(any());
        verify(roleWriteAuditMarker, never()).rolePermissionsChanged();
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
        ArgumentCaptor<List<Long>> captor = ArgumentCaptor.forClass(List.class);
        verify(sysRolePermissionMapper).insertPermissions(org.mockito.ArgumentMatchers.eq(37L),
                captor.capture());
        assertThat(captor.getValue()).containsExactly(1L, 2L, 3L);
    }

    /** 现有关联为 null（mapper 防御）按空集合比较，非空目标走变更路径。 */
    @Test
    void replacePermissionsTreatsNullCurrentAsEmpty() {
        when(sysRoleMapper.selectByIdForUpdate(37L)).thenReturn(role(37L));
        when(sysPermissionMapper.selectByIdsForShare(List.of(1L)))
                .thenReturn(List.of(permission(1L, 1)));
        when(sysRolePermissionMapper.selectPermissionIdsByRoleId(37L)).thenReturn(null);

        service.replacePermissions(37L, new RolePermissionsUpdateRequest(List.of(1L)));

        verify(sysRolePermissionMapper).deleteByRoleId(37L);
        verify(roleWriteAuditMarker).rolePermissionsChanged();
    }
}
