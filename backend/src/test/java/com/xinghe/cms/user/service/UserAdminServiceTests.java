package com.xinghe.cms.user.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.xinghe.cms.common.contract.RoleRef;
import com.xinghe.cms.common.exception.BusinessException;
import com.xinghe.cms.common.response.ResultCode;
import com.xinghe.cms.permission.service.RoleDirectoryService;
import com.xinghe.cms.security.PasswordHasher;
import com.xinghe.cms.user.dto.UserCreateRequest;
import com.xinghe.cms.user.dto.UserListQuery;
import com.xinghe.cms.user.dto.UserRolesUpdateRequest;
import com.xinghe.cms.user.dto.UserUpdateRequest;
import com.xinghe.cms.user.mapper.UserAdminMapper;
import com.xinghe.cms.user.mapper.row.UserProfileRow;
import com.xinghe.cms.user.mapper.row.UserRoleRow;
import com.xinghe.cms.user.mapper.row.UserStatusSnapshot;
import com.xinghe.cms.user.vo.UserPageVO;
import com.xinghe.cms.user.vo.UserVO;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * 员工管理服务的事务/契约回归（纯 Mockito，不依赖 MySQL/Redis）。
 *
 * <p>重点：部门祖先链启用校验、唯一键 409、幂等、角色替换的 MANDATORY 事务调用顺序、
 * 分页关键字转义与后代部门过滤。</p>
 */
@ExtendWith(MockitoExtension.class)
class UserAdminServiceTests {

    private static final long USER_ID = 7L;
    private static final LocalDateTime TIME = LocalDateTime.of(2026, 11, 2, 16, 0, 0);
    /** replaceRoles 只用 findStatusForUpdate 判存在，版本字段不参与断言。 */
    private static final UserStatusSnapshot ACTIVE = new UserStatusSnapshot(1, 0L);

    @Mock
    private PlatformTransactionManager transactionManager;
    @Mock
    private UserAdminMapper mapper;
    @Mock
    private PasswordHasher passwordHasher;
    @Mock
    private RoleDirectoryService roleDirectory;

    private UserAdminService service;

    @BeforeEach
    void setUp() {
        lenient().when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(new SimpleTransactionStatus());
        service = new UserAdminService(transactionManager, mapper, passwordHasher, roleDirectory,
                new UserAuditedActions());
    }

    private UserProfileRow profile(Long departmentId) {
        return new UserProfileRow(USER_ID, "editor_demo", "演示编辑", departmentId,
                departmentId == null ? null : "研发部门", 1, TIME, TIME);
    }

    @Test
    void createUserHashesPasswordAndReadsBackDatabaseTimestamps() {
        when(passwordHasher.hash("12345678")).thenReturn("pbkdf2$hash");
        when(mapper.insertUser("editor_demo", "pbkdf2$hash", "演示编辑", null)).thenReturn(1);
        when(mapper.findProfileByUsername("editor_demo")).thenReturn(profile(null));

        UserVO result = service.createUser(
                new UserCreateRequest("editor_demo", "12345678", "演示编辑", null));

        assertEquals("7", result.id());
        assertEquals(List.of(), result.roles());
        assertEquals("2026-11-02T16:00:00.000", result.createdAt());
        verify(transactionManager).commit(any());
        verify(mapper, never()).insertUserRoles(eq(USER_ID), anyList());
    }

    @Test
    void createUserChecksWholeDepartmentChain() {
        when(mapper.findDepartmentChainStatuses(3L)).thenReturn(List.of(1, 0, 1));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.createUser(new UserCreateRequest("u", "12345678", "姓名", 3L)));

        assertEquals(ResultCode.CONFLICT, exception.getResultCode());
        verify(mapper, never()).insertUser(any(), any(), any(), any());
        verify(transactionManager).rollback(any());
    }

    @Test
    void createUserMissingDepartmentReturns404() {
        when(mapper.findDepartmentChainStatuses(9L)).thenReturn(List.of());

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.createUser(new UserCreateRequest("u", "12345678", "姓名", 9L)));

        assertEquals(ResultCode.NOT_FOUND, exception.getResultCode());
        verify(mapper, never()).insertUser(any(), any(), any(), any());
    }

    @Test
    void duplicateUsernameMapsTo409Not500() {
        when(passwordHasher.hash("12345678")).thenReturn("pbkdf2$hash");
        when(mapper.insertUser(any(), any(), any(), any()))
                .thenThrow(new DuplicateKeyException("uk_user_username"));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.createUser(new UserCreateRequest("dup", "12345678", "姓名", null)));

        assertEquals(ResultCode.CONFLICT, exception.getResultCode());
        verify(transactionManager).rollback(any());
    }

    @Test
    void updateUserKeepsExistingDisabledDepartmentReference() {
        when(mapper.findProfileForUpdate(USER_ID)).thenReturn(profile(3L));
        when(mapper.updateProfile(USER_ID, "新姓名", 3L)).thenReturn(1);
        when(mapper.findProfileById(USER_ID)).thenReturn(new UserProfileRow(
                USER_ID, "editor_demo", "新姓名", 3L, "研发部门", 1, TIME, TIME));

        UserVO result = service.updateUser(USER_ID, new UserUpdateRequest("新姓名", 3L));

        assertEquals("新姓名", result.realName());
        assertEquals("3", result.departmentId());
        // 原引用不变：不检查祖先链，允许保留已停用部门。
        verify(mapper, never()).findDepartmentChainStatuses(any());
        verify(mapper).updateProfile(USER_ID, "新姓名", 3L);
    }

    @Test
    void updateUserNewDepartmentRequiresEnabledAncestors() {
        when(mapper.findProfileForUpdate(USER_ID)).thenReturn(profile(1L));
        when(mapper.findDepartmentChainStatuses(2L)).thenReturn(List.of(1, 0));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.updateUser(USER_ID, new UserUpdateRequest("新姓名", 2L)));

        assertEquals(ResultCode.CONFLICT, exception.getResultCode());
        verify(mapper, never()).updateProfile(any(), any(), any());
    }

    @Test
    void updateUserMissingUserReturns404() {
        when(mapper.findProfileForUpdate(USER_ID)).thenReturn(null);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.updateUser(USER_ID, new UserUpdateRequest("新姓名", null)));

        assertEquals(ResultCode.NOT_FOUND, exception.getResultCode());
        verify(transactionManager).rollback(any());
    }

    @Test
    void replaceRolesValidatesThenReplacesInOneTransaction() {
        when(mapper.findStatusForUpdate(USER_ID)).thenReturn(ACTIVE);
        when(roleDirectory.lockAssignableRoles(List.of(2L, 3L))).thenReturn(List.of(
                new RoleRef(2L, "EDITOR", "内容编辑"), new RoleRef(3L, "REVIEWER", "审核员")));
        when(mapper.findRoleIdsByUserId(USER_ID)).thenReturn(List.of(1L));

        service.replaceRoles(USER_ID, new UserRolesUpdateRequest(List.of(3L, 2L)));

        verify(roleDirectory).lockAssignableRoles(List.of(2L, 3L));
        verify(mapper).deleteUserRoles(USER_ID);
        verify(mapper).insertUserRoles(USER_ID, List.of(2L, 3L));
        verify(transactionManager).commit(any());
    }

    @Test
    void replaceRolesSameSetStillValidatesButDoesNotRewrite() {
        when(mapper.findStatusForUpdate(USER_ID)).thenReturn(ACTIVE);
        when(roleDirectory.lockAssignableRoles(List.of(2L)))
                .thenReturn(List.of(new RoleRef(2L, "EDITOR", "内容编辑")));
        when(mapper.findRoleIdsByUserId(USER_ID)).thenReturn(List.of(2L));

        service.replaceRoles(USER_ID, new UserRolesUpdateRequest(List.of(2L)));

        verify(roleDirectory).lockAssignableRoles(List.of(2L));
        verify(mapper, never()).deleteUserRoles(any());
        verify(mapper, never()).insertUserRoles(any(), anyList());
    }

    @Test
    void replaceRolesDisabledRoleRollsBackWithoutWrites() {
        when(mapper.findStatusForUpdate(USER_ID)).thenReturn(ACTIVE);
        when(roleDirectory.lockAssignableRoles(List.of(2L)))
                .thenThrow(new BusinessException(ResultCode.CONFLICT, "角色已停用"));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.replaceRoles(USER_ID, new UserRolesUpdateRequest(List.of(2L))));

        assertEquals(ResultCode.CONFLICT, exception.getResultCode());
        verify(mapper, never()).deleteUserRoles(any());
        verify(transactionManager).rollback(any());
    }

    @Test
    void replaceRolesMissingUserReturns404() {
        when(mapper.findStatusForUpdate(USER_ID)).thenReturn(null);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.replaceRoles(USER_ID, new UserRolesUpdateRequest(List.of())));

        assertEquals(ResultCode.NOT_FOUND, exception.getResultCode());
        verifyNoInteractions(roleDirectory);
    }

    @Test
    void listUsersEscapesWildcardsAndFiltersWholeSubtree() {
        when(mapper.findDescendantDepartmentIds(2L)).thenReturn(List.of(2L, 5L));
        when(mapper.countUsers("%a!%b!_c%", List.of(2L, 5L), null)).thenReturn(1L);
        when(mapper.findUsersPage("%a!%b!_c%", List.of(2L, 5L), null, 10L, 10L))
                .thenReturn(List.of(profile(2L)));
        when(mapper.findRolesByUserIds(List.of(USER_ID))).thenReturn(List.of(
                new UserRoleRow(USER_ID, 3L, "EDITOR", "内容编辑", 0)));

        UserPageVO page = service.listUsers(new UserListQuery(2L, 10L, "a%b_c", 2L, null));

        assertEquals(1, page.records().size());
        assertEquals("3", page.records().get(0).roles().get(0).id());
        assertEquals(0, page.records().get(0).roles().get(0).status());
        assertEquals(1L, page.total());
    }

    @Test
    void listUsersWithUnknownDepartmentReturnsEmptyPage() {
        when(mapper.findDescendantDepartmentIds(99L)).thenReturn(List.of());

        UserPageVO page = service.listUsers(new UserListQuery(1L, 10L, null, 99L, null));

        assertEquals(List.of(), page.records());
        assertEquals(0L, page.total());
        verify(mapper, never()).countUsers(any(), any(), any());
    }

    @Test
    void listUsersPassesStatusFilterAndTrimsKeyword() {
        when(mapper.countUsers("%abc%", null, 1)).thenReturn(0L);

        service.listUsers(new UserListQuery(1L, 10L, "  abc  ", null, 1));

        ArgumentCaptor<String> pattern = ArgumentCaptor.forClass(String.class);
        verify(mapper).countUsers(pattern.capture(), eq(null), eq(1));
        assertEquals("%abc%", pattern.getValue());
    }

    @Test
    void getUserMissingReturns404() {
        when(mapper.findProfileById(USER_ID)).thenReturn(null);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.getUser(USER_ID));

        assertEquals(ResultCode.NOT_FOUND, exception.getResultCode());
    }
}
