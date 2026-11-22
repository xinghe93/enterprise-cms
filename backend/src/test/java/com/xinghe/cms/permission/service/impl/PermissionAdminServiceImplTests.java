package com.xinghe.cms.permission.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.xinghe.cms.common.contract.CurrentUser;
import com.xinghe.cms.common.exception.BusinessException;
import com.xinghe.cms.common.response.ResultCode;
import com.xinghe.cms.permission.entity.SysPermission;
import com.xinghe.cms.permission.mapper.OperatorRowLockMapper;
import com.xinghe.cms.permission.mapper.SysPermissionMapper;
import com.xinghe.cms.permission.request.PermissionCreateRequest;
import com.xinghe.cms.permission.request.PermissionStatusRequest;
import com.xinghe.cms.permission.request.PermissionUpdateRequest;
import com.xinghe.cms.permission.response.PermissionView;
import com.xinghe.cms.security.CurrentUserProvider;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 权限项管理写服务单测（Mockito 桩、不连库）。覆盖：创建（默认启用、编码/名称
 * 去空白、唯一键冲突 409、类型条件不变量 400：API 路径结构/方法枚举、MENU/BUTTON
 * method 必须 null）、修改（按数据库类型校验、404、显式 null 合法）、启停
 * （排他锁下幂等跳过、变更经审计代理执行）、<b>取锁顺序</b>（评审 P2：事务
 * 开头先共享锁操作人用户行、再触碰权限行）。审计随事务提交由
 * {@code PermissionAdminAuditTransactionTests} 覆盖；SQL 与行锁由联调验证。
 */
@ExtendWith(MockitoExtension.class)
class PermissionAdminServiceImplTests {

    @Mock
    private SysPermissionMapper sysPermissionMapper;

    @Mock
    private OperatorRowLockMapper operatorRowLockMapper;

    @Mock
    private PermissionWriteAuditMarker permissionWriteAuditMarker;

    @Mock
    private CurrentUserProvider currentUserProvider;

    @InjectMocks
    private PermissionAdminServiceImpl service;

    @BeforeEach
    void executeWriteActionInMarkerStub() {
        // 生产里审计代理（受 @AuditLog 环绕）会执行传入的状态更新 Runnable；
        // 单测中 marker 是 mock，需让其真正执行 Runnable，update 断言才成立。
        // lenient：幂等 / 404 / 400 用例不调用 marker，避免 Mockito 严格桩判未使用。
        Mockito.lenient().doAnswer(invocation -> {
            Runnable writeAction = invocation.getArgument(0);
            writeAction.run();
            return null;
        }).when(permissionWriteAuditMarker).permissionStatusChanged(any(Runnable.class));
    }

    private static SysPermission permission(long id, String code, String type, int status) {
        SysPermission entity = new SysPermission();
        entity.setId(id);
        entity.setPermissionCode(code);
        entity.setPermissionName("旧名称");
        entity.setPermissionType(type);
        entity.setPath("API".equals(type) ? "/api/admin/roles/{id}" : null);
        entity.setMethod("API".equals(type) ? "GET" : null);
        entity.setStatus(status);
        entity.setCreatedAt(LocalDateTime.of(2026, 11, 21, 9, 0));
        entity.setUpdatedAt(LocalDateTime.of(2026, 11, 21, 9, 0));
        return entity;
    }

    // ---------- 创建（POST /api/admin/permissions） ----------

    /** 创建成功：默认启用、编码/名称去空白写库、重新查询返回数据库生成时间。 */
    @Test
    void createPersistsTrimmedValuesWithEnabledStatusAndReturnsDbTimes() {
        // 模拟数据库自增：插入时回填主键，随后的 selectById 才能取回创建结果
        Mockito.doAnswer(invocation -> {
            ((SysPermission) invocation.getArgument(0)).setId(77L);
            return 1;
        }).when(sysPermissionMapper).insert(any(SysPermission.class));
        SysPermission saved = permission(77L, "article:read", "API", 1);
        when(sysPermissionMapper.selectById(77L)).thenReturn(saved);

        PermissionView view = service.create(new PermissionCreateRequest(
                " article:read ", " 后台文章详情 ", "API", "/api/admin/articles/{id}", "GET"));

        ArgumentCaptor<SysPermission> captor = ArgumentCaptor.forClass(SysPermission.class);
        verify(sysPermissionMapper).insert(captor.capture());
        SysPermission inserted = captor.getValue();
        assertThat(inserted.getPermissionCode()).isEqualTo("article:read");
        assertThat(inserted.getPermissionName()).isEqualTo("后台文章详情");
        assertThat(inserted.getPermissionType()).isEqualTo("API");
        assertThat(inserted.getStatus()).isEqualTo(1);
        assertThat(inserted.getCreatedAt()).isNull();
        assertThat(inserted.getUpdatedAt()).isNull();
        assertThat(view.id()).isEqualTo("77");
        assertThat(view.createdAt()).isEqualTo(LocalDateTime.of(2026, 11, 21, 9, 0));
    }

    /** 重复编码（uk_permission_code）转 409，不落入 500 兜底。 */
    @Test
    void duplicateCodeBecomes409() {
        Mockito.doThrow(new org.springframework.dao.DuplicateKeyException("uk_permission_code"))
                .when(sysPermissionMapper).insert(any(SysPermission.class));

        assertThatThrownBy(() -> service.create(new PermissionCreateRequest(
                "article:read", "后台文章详情", "API", "/api/admin/articles/{id}", "GET")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getResultCode())
                .isEqualTo(ResultCode.CONFLICT);
    }

    /** 类型严格枚举：非 MENU/BUTTON/API 一律 400。 */
    @Test
    void unknownPermissionTypeReturns400() {
        assertThatThrownBy(() -> service.create(new PermissionCreateRequest(
                "x:y", "名称", "MENU2", null, null)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getResultCode())
                .isEqualTo(ResultCode.BAD_REQUEST);
    }

    /** API 类型缺 path / method → 400（OpenAPI oneOf required）。 */
    @Test
    void apiTypeWithoutPathOrMethodReturns400() {
        assertThatThrownBy(() -> service.create(new PermissionCreateRequest(
                "a:b", "名称", "API", null, "GET")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getResultCode())
                .isEqualTo(ResultCode.BAD_REQUEST);

        assertThatThrownBy(() -> service.create(new PermissionCreateRequest(
                "a:b", "名称", "API", "/api/admin/articles/{id}", null)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getResultCode())
                .isEqualTo(ResultCode.BAD_REQUEST);
    }

    /** API 路径：查询串 / 片段 / 空白（含 NBSP）一律 400；合法模板路径放行。 */
    @Test
    void apiPathValidationRejectsQueryFragmentAndWhitespace() {
        assertThatThrownBy(() -> service.create(new PermissionCreateRequest(
                "a:b", "名称", "API", "/api/admin/articles?id=1", "GET")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getResultCode())
                .isEqualTo(ResultCode.BAD_REQUEST);

        assertThatThrownBy(() -> service.create(new PermissionCreateRequest(
                "a:b", "名称", "API", "/api/admin/articles#frag", "GET")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getResultCode())
                .isEqualTo(ResultCode.BAD_REQUEST);

        assertThatThrownBy(() -> service.create(new PermissionCreateRequest(
                "a:b", "名称", "API", "/api/admin/art\u00A0icles", "GET")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getResultCode())
                .isEqualTo(ResultCode.BAD_REQUEST);

        assertThatThrownBy(() -> service.create(new PermissionCreateRequest(
                "a:b", "名称", "API", "/api/other/articles", "GET")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getResultCode())
                .isEqualTo(ResultCode.BAD_REQUEST);

        // 合法：{id} 模板与无后缀前缀
        when(sysPermissionMapper.selectById(any())).thenReturn(permission(1L, "a:b", "API", 1));
        service.create(new PermissionCreateRequest("a:b", "名称", "API", "/api/portal/articles/{id}", "GET"));
        service.create(new PermissionCreateRequest("a:c", "名称", "API", "/api/auth", "POST"));
    }

    /** method 大写枚举严格匹配；MENU/BUTTON 的 method 必须 null。 */
    @Test
    void methodEnumAndMenuButtonInvariantReturn400() {
        assertThatThrownBy(() -> service.create(new PermissionCreateRequest(
                "a:b", "名称", "API", "/api/admin/x", "get")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getResultCode())
                .isEqualTo(ResultCode.BAD_REQUEST);

        assertThatThrownBy(() -> service.create(new PermissionCreateRequest(
                "a:b", "名称", "API", "/api/admin/x", "FETCH")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getResultCode())
                .isEqualTo(ResultCode.BAD_REQUEST);

        assertThatThrownBy(() -> service.create(new PermissionCreateRequest(
                "m:n", "名称", "MENU", "/articles", "GET")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getResultCode())
                .isEqualTo(ResultCode.BAD_REQUEST);

        assertThatThrownBy(() -> service.create(new PermissionCreateRequest(
                "b:n", "名称", "BUTTON", null, "POST")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getResultCode())
                .isEqualTo(ResultCode.BAD_REQUEST);
    }

    /** 编码/名称裁剪后为空白返回 400；长度超限返回 400。 */
    @Test
    void blankAfterStripAndLengthOverflowReturn400() {
        assertThatThrownBy(() -> service.create(new PermissionCreateRequest(
                "\u00A0", "名称", "MENU", null, null)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getResultCode())
                .isEqualTo(ResultCode.BAD_REQUEST);

        assertThatThrownBy(() -> service.create(new PermissionCreateRequest(
                "x".repeat(101), "名称", "MENU", null, null)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getResultCode())
                .isEqualTo(ResultCode.BAD_REQUEST);

        assertThatThrownBy(() -> service.create(new PermissionCreateRequest(
                "a:b", "名称", "API", "/api/admin/" + "x".repeat(250), "GET")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getResultCode())
                .isEqualTo(ResultCode.BAD_REQUEST);
    }

    // ---------- 修改（PUT /api/admin/permissions/{id}） ----------

    /** 修改成功：只更新 name/path/method 三列，重新查询返回数据库时间。 */
    @Test
    void updateWritesOnlyNamePathMethodAndReturnsDbTimes() {
        SysPermission existing = permission(9L, "article:read", "API", 1);
        when(sysPermissionMapper.selectById(9L)).thenReturn(existing);
        SysPermission saved = permission(9L, "article:read", "API", 1);
        saved.setPermissionName("新名称");
        when(sysPermissionMapper.selectById(9L)).thenReturn(existing, saved);

        PermissionView view = service.update(9L, new PermissionUpdateRequest(
                " 新名称 ", "/api/admin/articles/{id}", "PUT"));

        verify(sysPermissionMapper).update(any(), any(Wrapper.class));
        assertThat(view.permissionName()).isEqualTo("新名称");
        assertThat(view.updatedAt()).isEqualTo(LocalDateTime.of(2026, 11, 21, 9, 0));
    }

    /** 修改 MENU 类型：显式 null path/method 合法（写 NULL，不被跳过）。 */
    @Test
    void updateMenuTypeAcceptsExplicitNulls() {
        SysPermission existing = permission(10L, "menu:articles", "MENU", 1);
        SysPermission saved = permission(10L, "menu:articles", "MENU", 1);
        when(sysPermissionMapper.selectById(10L)).thenReturn(existing, saved);

        service.update(10L, new PermissionUpdateRequest("文章菜单", null, null));

        verify(sysPermissionMapper).update(any(), any(Wrapper.class));
    }

    /** 修改时按数据库类型执行条件不变量：MENU 带 method 400、API 缺 method 400。 */
    @Test
    void updateValidatesTypeInvariantAgainstDatabaseType() {
        SysPermission menu = permission(10L, "menu:articles", "MENU", 1);
        when(sysPermissionMapper.selectById(10L)).thenReturn(menu);
        assertThatThrownBy(() -> service.update(10L,
                new PermissionUpdateRequest("菜单", "/articles", "GET")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getResultCode())
                .isEqualTo(ResultCode.BAD_REQUEST);
        verify(sysPermissionMapper, never()).update(any(), any(Wrapper.class));

        SysPermission api = permission(9L, "article:read", "API", 1);
        when(sysPermissionMapper.selectById(9L)).thenReturn(api);
        assertThatThrownBy(() -> service.update(9L,
                new PermissionUpdateRequest("名称", "/api/admin/articles/{id}", null)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getResultCode())
                .isEqualTo(ResultCode.BAD_REQUEST);
        verify(sysPermissionMapper, never()).update(any(), any(Wrapper.class));
    }

    /** 不存在 404：锁定读/查询返回 null，无更新。 */
    @Test
    void updateMissingPermissionReturns404() {
        when(sysPermissionMapper.selectById(999L)).thenReturn(null);

        assertThatThrownBy(() -> service.update(999L,
                new PermissionUpdateRequest("名称", null, null)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getResultCode())
                .isEqualTo(ResultCode.NOT_FOUND);
        verify(sysPermissionMapper, never()).update(any(), any(Wrapper.class));
    }

    // ---------- 启停（PUT /api/admin/permissions/{id}/status） ----------

    /** 状态实际变更：排他锁读 → 幂等判断 → 经审计代理执行更新。 */
    @Test
    void statusChangeRunsUpdateInsideAuditProxy() {
        when(sysPermissionMapper.selectByIdForUpdate(9L))
                .thenReturn(permission(9L, "article:read", "API", 1));
        when(sysPermissionMapper.update(any(SysPermission.class), any(Wrapper.class))).thenReturn(1);

        service.setStatus(9L, new PermissionStatusRequest(0));

        verify(permissionWriteAuditMarker).permissionStatusChanged(any(Runnable.class));
        ArgumentCaptor<SysPermission> captor = ArgumentCaptor.forClass(SysPermission.class);
        verify(sysPermissionMapper).update(captor.capture(), any(Wrapper.class));
        assertThat(captor.getValue().getStatus()).isEqualTo(0);
    }

    /** 重复同状态：幂等返回 200，不写库、不进审计代理。 */
    @Test
    void idempotentSameStatusSkipsWriteAndAudit() {
        when(sysPermissionMapper.selectByIdForUpdate(9L))
                .thenReturn(permission(9L, "article:read", "API", 1));

        service.setStatus(9L, new PermissionStatusRequest(1));

        verify(sysPermissionMapper, never()).update(any(SysPermission.class), any(Wrapper.class));
        verify(permissionWriteAuditMarker, never()).permissionStatusChanged(any(Runnable.class));
    }

    /** 不存在 404：发生在受审计代理之前，无写入、无审计。 */
    @Test
    void statusOfMissingPermissionReturns404WithoutAudit() {
        when(sysPermissionMapper.selectByIdForUpdate(999L)).thenReturn(null);

        assertThatThrownBy(() -> service.setStatus(999L, new PermissionStatusRequest(0)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getResultCode())
                .isEqualTo(ResultCode.NOT_FOUND);
        verify(sysPermissionMapper, never()).update(any(SysPermission.class), any(Wrapper.class));
        verify(permissionWriteAuditMarker, never()).permissionStatusChanged(any(Runnable.class));
    }

    /** status 非 0/1 → 400，且不锁定、不写库。 */
    @Test
    void invalidStatusValueReturns400WithoutLocking() {
        assertThatThrownBy(() -> service.setStatus(9L, new PermissionStatusRequest(2)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getResultCode())
                .isEqualTo(ResultCode.BAD_REQUEST);
        verifyNoInteractions(sysPermissionMapper);
    }

    /** 审计代理内的写入抛错：异常冒泡（真实链路的回滚 + FAILURE 见事务测试类）。 */
    @Test
    void updateFailureInsideProxyPropagates() {
        when(sysPermissionMapper.selectByIdForUpdate(9L))
                .thenReturn(permission(9L, "article:read", "API", 1));
        when(sysPermissionMapper.update(any(SysPermission.class), any(Wrapper.class)))
                .thenThrow(new IllegalStateException("模拟更新失败"));

        assertThatThrownBy(() -> service.setStatus(9L, new PermissionStatusRequest(0)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("模拟更新失败");
    }

    /**
     * 评审 P2 锁序回归：事务开头先 {@code FOR SHARE} 共享锁操作人用户行，
     * 之后才锁定/触碰权限行——统一"用户 → 权限"顺序，打破"权限修改/启停 ×
     * 角色权限替换 × 用户角色分配"三事务并发下的 P→U→R→P 锁环。
     */
    @Test
    void operatorSharedLockIsTakenBeforePermissionRowLock() {
        when(currentUserProvider.requireCurrentUser())
                .thenReturn(new CurrentUser(501L, "admin", null));
        when(operatorRowLockMapper.selectOperatorForShare(501L)).thenReturn(501L);
        when(sysPermissionMapper.selectByIdForUpdate(9L))
                .thenReturn(permission(9L, "article:read", "API", 1));
        when(sysPermissionMapper.update(any(SysPermission.class), any(Wrapper.class))).thenReturn(1);

        service.setStatus(9L, new PermissionStatusRequest(0));

        org.mockito.InOrder ordered = inOrder(operatorRowLockMapper, sysPermissionMapper);
        ordered.verify(operatorRowLockMapper).selectOperatorForShare(501L);
        ordered.verify(sysPermissionMapper).selectByIdForUpdate(9L);
        ordered.verify(sysPermissionMapper).update(any(SysPermission.class), any(Wrapper.class));
    }

    /** 无认证上下文（单测/内部调用）：跳过操作人锁，不阻断业务（与切面记 null user_id 一致）。 */
    @Test
    void missingAuthContextSkipsOperatorLockWithoutBlocking() {
        when(sysPermissionMapper.selectByIdForUpdate(9L))
                .thenReturn(permission(9L, "article:read", "API", 1));
        when(sysPermissionMapper.update(any(SysPermission.class), any(Wrapper.class))).thenReturn(1);

        service.setStatus(9L, new PermissionStatusRequest(0));

        verifyNoInteractions(operatorRowLockMapper);
        verify(sysPermissionMapper).update(any(SysPermission.class), any(Wrapper.class));
    }
}
