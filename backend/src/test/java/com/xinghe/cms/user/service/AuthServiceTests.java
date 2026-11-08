package com.xinghe.cms.user.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
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
import com.xinghe.cms.user.mapper.row.RoleBriefRow;
import com.xinghe.cms.user.mapper.row.UserCredentialsRow;
import com.xinghe.cms.user.mapper.row.UserProfileRow;
import com.xinghe.cms.user.vo.LoginResultVO;
import com.xinghe.cms.user.vo.UserVO;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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

    @Test
    void loginTrimsUsernameAndReturnsExactContractFields() {
        when(userMapper.findCredentialsByUsername("editor_demo"))
                .thenReturn(new UserCredentialsRow(7L, "editor_demo", "stored-hash", 1));
        when(passwordHasher.matches("pw", "stored-hash")).thenReturn(true);
        when(userMapper.findProfileById(7L)).thenReturn(profile());
        when(userMapper.findRolesByUserId(7L)).thenReturn(List.of(
                new RoleBriefRow(9L, "ADMIN", "管理员", 0),
                new RoleBriefRow(2L, "EDITOR", "内容编辑", 1)));
        when(sessions.issue(7L)).thenReturn("opaque-token");

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
        verify(sessions).issue(7L);
    }

    @Test
    void unknownUserIsGenericUnauthorizedAndIssuesNoToken() {
        when(userMapper.findCredentialsByUsername("ghost")).thenReturn(null);
        BusinessException exception = assertThrows(BusinessException.class,
                () -> authService.login(new LoginRequest("ghost", "pw")));
        assertEquals(ResultCode.UNAUTHORIZED, exception.getResultCode());
        assertEquals("用户名或密码错误", exception.getMessage());
        verifyNoInteractions(passwordHasher, sessions);
    }

    @Test
    void disabledUserIsGenericUnauthorizedAndIssuesNoToken() {
        when(userMapper.findCredentialsByUsername("editor_demo"))
                .thenReturn(new UserCredentialsRow(7L, "editor_demo", "stored-hash", 0));
        BusinessException exception = assertThrows(BusinessException.class,
                () -> authService.login(new LoginRequest("editor_demo", "pw")));
        assertEquals(ResultCode.UNAUTHORIZED, exception.getResultCode());
        assertEquals("用户名或密码错误", exception.getMessage());
        verifyNoInteractions(passwordHasher);
        verify(sessions, never()).issue(any());
    }

    @Test
    void wrongPasswordIsGenericUnauthorizedAndIssuesNoToken() {
        when(userMapper.findCredentialsByUsername("editor_demo"))
                .thenReturn(new UserCredentialsRow(7L, "editor_demo", "stored-hash", 1));
        when(passwordHasher.matches("wrong", "stored-hash")).thenReturn(false);
        BusinessException exception = assertThrows(BusinessException.class,
                () -> authService.login(new LoginRequest("editor_demo", "wrong")));
        assertEquals("用户名或密码错误", exception.getMessage());
        verify(sessions, never()).issue(any());
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
