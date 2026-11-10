package com.xinghe.cms.user.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.xinghe.cms.common.exception.BusinessException;
import com.xinghe.cms.common.response.ResultCode;
import com.xinghe.cms.security.AuthSessionService;
import com.xinghe.cms.security.PasswordHasher;
import com.xinghe.cms.user.dto.LoginRequest;
import com.xinghe.cms.user.mapper.UserAuthMapper;
import com.xinghe.cms.user.mapper.row.CurrentUserStatusRow;
import com.xinghe.cms.user.mapper.row.RoleBriefRow;
import com.xinghe.cms.user.mapper.row.UserCredentialsRow;
import com.xinghe.cms.user.mapper.row.UserProfileRow;
import com.xinghe.cms.user.vo.LoginResultVO;
import com.xinghe.cms.user.vo.UserVO;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AuthServiceTests {

    private static final LocalDateTime TIME = LocalDateTime.of(2026, 11, 2, 16, 0, 0, 123_000_000);

    @Mock
    private UserAuthMapper userMapper;
    @Mock
    private PasswordHasher passwordHasher;
    @Mock
    private AuthSessionService sessions;
    @InjectMocks
    private AuthService authService;

    private UserProfileRow profile() {
        return new UserProfileRow(7L, "editor_demo", "演示编辑", null, null, 1, TIME, TIME);
    }

    private void stubEnabledCredentials() {
        when(userMapper.findCredentialsByUsername("editor_demo"))
                .thenReturn(new UserCredentialsRow(7L, "editor_demo", "stored-hash", 1));
        when(passwordHasher.matches("pw", "stored-hash")).thenReturn(true);
    }

    private void stubEnabledStatus() {
        when(userMapper.findStatusById(7L))
                .thenReturn(new CurrentUserStatusRow("editor_demo", null, 1));
    }

    private void stubProfile() {
        when(userMapper.findProfileById(7L)).thenReturn(profile());
        when(userMapper.findRolesByUserId(7L)).thenReturn(List.of());
    }

    @Test
    void loginTrimsUsernameAndReturnsExactContractFields() {
        stubEnabledCredentials();
        when(sessions.ensureEpoch(7L)).thenReturn("epoch-1");
        stubEnabledStatus();
        when(userMapper.findProfileById(7L)).thenReturn(profile());
        when(userMapper.findRolesByUserId(7L)).thenReturn(List.of(
                new RoleBriefRow(9L, "ADMIN", "管理员", 0),
                new RoleBriefRow(2L, "EDITOR", "内容编辑", 1)));
        when(sessions.issue(7L, "epoch-1")).thenReturn("opaque-token");

        LoginResultVO result = authService.login(new LoginRequest("  editor_demo  ", "pw"));

        assertEquals("opaque-token", result.accessToken());
        assertEquals("Bearer", result.tokenType());
        assertEquals(AuthSessionService.TTL_SECONDS, result.expiresIn());
        UserVO user = result.user();
        assertEquals("7", user.id());
        assertEquals("editor_demo", user.username());
        assertNull(user.departmentId());
        assertNull(user.departmentName());
        assertEquals("2026-11-02T16:00:00.123", user.createdAt());
        assertEquals(List.of("2", "9"), user.roles().stream().map(r -> r.id()).toList());
        assertEquals(0, user.roles().get(1).status(), "停用角色仍用于回显");
        verify(sessions).issue(7L, "epoch-1");
    }

    /** 复审①顺序：密码校验通过后、状态复查前取得确定纪元，最后带该纪元签发。 */
    @Test
    void loginObtainsEpochBetweenPasswordCheckAndStatusRecheck() {
        stubEnabledCredentials();
        when(sessions.ensureEpoch(7L)).thenReturn("epoch-1");
        stubEnabledStatus();
        stubProfile();
        when(sessions.issue(7L, "epoch-1")).thenReturn("opaque-token");

        authService.login(new LoginRequest("editor_demo", "pw"));

        InOrder order = inOrder(passwordHasher, sessions, userMapper);
        order.verify(passwordHasher).matches("pw", "stored-hash");
        order.verify(sessions).ensureEpoch(7L);
        order.verify(userMapper).findStatusById(7L);
        order.verify(sessions).issue(7L, "epoch-1");
    }

    /** 首次登录也由 ensureEpoch 原子创建并返回确定纪元，不再传「空纪元」去签发。 */
    @Test
    void loginOnFirstEverSessionIssuesWithEnsuredEpoch() {
        stubEnabledCredentials();
        when(sessions.ensureEpoch(7L)).thenReturn("fresh-epoch");
        stubEnabledStatus();
        stubProfile();
        when(sessions.issue(7L, "fresh-epoch")).thenReturn("opaque-token");

        LoginResultVO result = authService.login(new LoginRequest("editor_demo", "pw"));

        assertEquals("opaque-token", result.accessToken());
        verify(sessions).issue(7L, "fresh-epoch");
    }

    /** 并发停用：取纪元后复查状态看到已停用，必须拒绝，不能签发任何 Token。 */
    @Test
    void loginIsRejectedWhenStatusRecheckSeesDisabledAccount() {
        stubEnabledCredentials();
        when(sessions.ensureEpoch(7L)).thenReturn("epoch-1");
        when(userMapper.findStatusById(7L))
                .thenReturn(new CurrentUserStatusRow("editor_demo", null, 0));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> authService.login(new LoginRequest("editor_demo", "pw")));

        assertEquals(ResultCode.UNAUTHORIZED, exception.getResultCode());
        assertEquals("用户名或密码错误", exception.getMessage());
        verify(sessions, never()).issue(any(), any());
        verify(userMapper, never()).findProfileById(any());
    }

    /** 并发停用：撤销发生在复查之后，签发必须按旧纪元比对失败而拒绝。 */
    @Test
    void loginIsRejectedWhenEpochChangedBeforeIssuing() {
        stubEnabledCredentials();
        when(sessions.ensureEpoch(7L)).thenReturn("epoch-1");
        stubEnabledStatus();
        stubProfile();
        when(sessions.issue(7L, "epoch-1")).thenReturn(null);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> authService.login(new LoginRequest("editor_demo", "pw")));

        assertEquals(ResultCode.UNAUTHORIZED, exception.getResultCode());
        assertEquals("用户名或密码错误", exception.getMessage());
        verify(sessions).issue(7L, "epoch-1");
    }

    @Test
    void unknownUserIsGenericUnauthorizedAndIssuesNoToken() {
        when(userMapper.findCredentialsByUsername("ghost")).thenReturn(null);
        BusinessException exception = assertThrows(BusinessException.class,
                () -> authService.login(new LoginRequest("ghost", "pw")));
        assertEquals(ResultCode.UNAUTHORIZED, exception.getResultCode());
        assertEquals("用户名或密码错误", exception.getMessage());
        verify(passwordHasher).matches(eq("pw"), anyString());
        verifyNoInteractions(sessions);
    }

    @Test
    void disabledUserIsGenericUnauthorizedAndIssuesNoToken() {
        when(userMapper.findCredentialsByUsername("editor_demo"))
                .thenReturn(new UserCredentialsRow(7L, "editor_demo", "stored-hash", 0));
        BusinessException exception = assertThrows(BusinessException.class,
                () -> authService.login(new LoginRequest("editor_demo", "pw")));
        assertEquals(ResultCode.UNAUTHORIZED, exception.getResultCode());
        assertEquals("用户名或密码错误", exception.getMessage());
        verify(passwordHasher).matches(eq("pw"), anyString());
        verify(sessions, never()).ensureEpoch(any());
        verify(sessions, never()).issue(any(), any());
    }

    @Test
    void wrongPasswordIsGenericUnauthorizedAndIssuesNoToken() {
        when(userMapper.findCredentialsByUsername("editor_demo"))
                .thenReturn(new UserCredentialsRow(7L, "editor_demo", "stored-hash", 1));
        when(passwordHasher.matches("wrong", "stored-hash")).thenReturn(false);
        BusinessException exception = assertThrows(BusinessException.class,
                () -> authService.login(new LoginRequest("editor_demo", "wrong")));
        assertEquals("用户名或密码错误", exception.getMessage());
        verify(sessions, never()).ensureEpoch(any());
        verify(sessions, never()).issue(any(), any());
    }

    @Test
    void currentUserWithoutProfileIsUnauthorized() {
        when(userMapper.findProfileById(7L)).thenReturn(null);
        BusinessException exception = assertThrows(BusinessException.class,
                () -> authService.currentUser(7L));
        assertEquals(ResultCode.UNAUTHORIZED, exception.getResultCode());
    }

    @Test
    void logoutRevokesOnlyCurrentSession() {
        authService.logout("opaque-token");
        verify(sessions).revoke("opaque-token");
        verify(sessions, never()).revokeAllForUser(any());
    }
}
