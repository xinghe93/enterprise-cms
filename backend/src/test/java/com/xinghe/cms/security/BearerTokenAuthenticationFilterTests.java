package com.xinghe.cms.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.xinghe.cms.common.contract.CurrentUser;
import com.xinghe.cms.security.AuthSessionService.SessionIdentity;
import com.xinghe.cms.user.mapper.UserAuthMapper;
import com.xinghe.cms.user.mapper.row.CurrentUserStatusRow;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class BearerTokenAuthenticationFilterTests {

    @Mock
    private AuthSessionService sessions;
    @Mock
    private UserAuthMapper userMapper;

    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private BearerTokenAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        filter = new BearerTokenAuthenticationFilter(sessions, userMapper);
    }

    private MockHttpServletRequest request(String path, String authorization) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setServletPath(path);
        if (authorization != null) {
            request.addHeader("Authorization", authorization);
        }
        return request;
    }

    private Map<?, ?> body(MockHttpServletResponse response) throws Exception {
        return jsonMapper.readValue(response.getContentAsString(), Map.class);
    }

    @Test
    void loginPathSkipsAuthentication() throws Exception {
        MockHttpServletRequest request = request("/api/auth/login", null);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean continued = new AtomicBoolean(false);
        filter.doFilter(request, response, (req, res) -> continued.set(true));
        assertTrue(continued.get());
        assertEquals(200, response.getStatus());
        assertNull(request.getAttribute(CurrentUserProvider.REQUEST_ATTRIBUTE));
    }

    @Test
    void loginPathVariantsAreNeverTreatedAsPublic() throws Exception {
        for (String path : List.of("/api/auth/login/", "/api/auth/login/extra",
                "/API/auth/login", "/api/auth/login;x=1")) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            AtomicBoolean continued = new AtomicBoolean(false);
            filter.doFilter(request(path, null), response, (req, res) -> continued.set(true));
            assertEquals(401, response.getStatus(), path + " 不得被当作 PUBLIC 端点放行");
            assertTrue(!continued.get(), path);
        }
    }

    @Test
    void encodedApiPathIsStillProtected() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/%61pi/auth/me");
        request.setServletPath("/api/auth/me");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean continued = new AtomicBoolean(false);
        filter.doFilter(request, response, (req, res) -> continued.set(true));
        assertEquals(401, response.getStatus(), "编码变体不得绕过 /api/ 前缀判断");
        assertFalse(continued.get(), "旧会话不得放行到后续过滤器");
    }

    @Test
    void missingBearerTokenReturnsUnauthorizedEnvelope() throws Exception {
        MockHttpServletRequest request = request("/api/auth/me", null);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean continued = new AtomicBoolean(false);
        filter.doFilter(request, response, (req, res) -> continued.set(true));

        assertEquals(401, response.getStatus());
        Map<?, ?> json = body(response);
        assertEquals(401, json.get("code"));
        assertEquals("请先登录", json.get("message"));
        assertNull(json.get("data"));
        assertTrue(json.containsKey("timestamp"));
        assertFalse(continued.get(), "旧会话不得放行到后续过滤器");
    }

    @Test
    void validSessionWritesVerifiedIdentityWithoutClientUserId() throws Exception {
        when(sessions.resolveIdentity("opaque-token")).thenReturn(new SessionIdentity(7L, "epoch-1"));
        when(userMapper.findStatusById(7L))
                .thenReturn(new CurrentUserStatusRow("editor_demo", 3L, 1));
        MockHttpServletRequest request = request("/api/auth/me", "Bearer opaque-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean continued = new AtomicBoolean(false);

        filter.doFilter(request, response, (req, res) -> continued.set(true));

        assertTrue(continued.get());
        CurrentUser current = (CurrentUser) request.getAttribute(CurrentUserProvider.REQUEST_ATTRIBUTE);
        assertEquals(7L, current.userId());
        assertEquals("editor_demo", current.username());
        assertEquals(3L, current.departmentId());
        assertEquals("opaque-token", request.getAttribute(CurrentUserProvider.TOKEN_ATTRIBUTE));
        // P1：状态确认后必须复核会话，正常路径同样完成两次解析。
        verify(sessions, times(2)).resolveIdentity("opaque-token");
    }

    @Test
    void revokedTokenIsUnauthorized() throws Exception {
        when(sessions.resolveIdentity("revoked")).thenReturn(null);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request("/api/auth/logout", "Bearer revoked"), response, (req, res) -> { });
        assertEquals(401, response.getStatus());
    }

    /** 停用路径改为按校验旧 Token 时确认的纪元做条件撤销，不再无条件轮换。 */
    @Test
    void disabledUserIsRejectedWithConditionalRevokeUsingObservedEpoch() throws Exception {
        when(sessions.resolveIdentity("opaque-token"))
                .thenReturn(new SessionIdentity(7L, "epoch-observed"));
        when(userMapper.findStatusById(7L)).thenReturn(new CurrentUserStatusRow("u", null, 0));
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean continued = new AtomicBoolean(false);

        filter.doFilter(request("/api/auth/me", "Bearer opaque-token"), response,
                (req, res) -> continued.set(true));

        assertEquals(401, response.getStatus(), "旧请求本身仍返回 401");
        assertFalse(continued.get(), "旧会话不得放行到后续过滤器");
        verify(sessions).revokeAllForUserIfEpoch(7L, "epoch-observed");
        verify(sessions, never()).revokeAllForUser(any());
    }

    /**
     * 复审②的确定性时序：旧请求读到停用后暂停，期间完成撤销、重新启用并重新登录；
     * 旧请求恢复后只能按过期纪元条件撤销（返回 false），新 Token 仍可访问受保护接口。
     */
    @Test
    void staleDisabledRequestDoesNotInvalidateSessionIssuedAfterReenable() throws Exception {
        when(sessions.resolveIdentity("old-token"))
                .thenReturn(new SessionIdentity(7L, "epoch-0"));
        when(sessions.resolveIdentity("new-token"))
                .thenReturn(new SessionIdentity(7L, "epoch-1"));
        // 第一次是旧请求看到的停用快照；期间账号已重新启用，第二次读到启用。
        when(userMapper.findStatusById(7L)).thenReturn(
                new CurrentUserStatusRow("u", null, 0),
                new CurrentUserStatusRow("u", null, 1));
        when(sessions.revokeAllForUserIfEpoch(7L, "epoch-0")).thenReturn(false);

        MockHttpServletResponse oldResponse = new MockHttpServletResponse();
        AtomicBoolean oldContinued = new AtomicBoolean(false);
        filter.doFilter(request("/api/auth/me", "Bearer old-token"), oldResponse,
                (req, res) -> oldContinued.set(true));
        assertEquals(401, oldResponse.getStatus());
        assertTrue(!oldContinued.get());
        verify(sessions).revokeAllForUserIfEpoch(7L, "epoch-0");
        verify(sessions, never()).revokeAllForUser(any());

        MockHttpServletResponse newResponse = new MockHttpServletResponse();
        AtomicBoolean newContinued = new AtomicBoolean(false);
        filter.doFilter(request("/api/auth/me", "Bearer new-token"), newResponse,
                (req, res) -> newContinued.set(true));
        assertEquals(200, newResponse.getStatus());
        assertTrue(newContinued.get(), "重新启用后签发的新 Token 仍可访问受保护接口");
    }

    /**
     * PR #39 审查 P1：停用与重新启用并发时不得凭旧会话放行。
     * 时序：首次解析确认旧会话（epoch-0）→ 状态查询期间停用提交、撤销把纪元轮换到
     * epoch-2 并完成重新启用 → 状态查询读到启用(1) → 二次解析发现纪元已变返回 null → 401。
     */
    @Test
    void staleSessionIsRejectedWhenStatusReadAfterReenable() throws Exception {
        AtomicReference<String> currentEpoch = new AtomicReference<>("epoch-0");
        when(sessions.resolveIdentity("old-token")).thenAnswer(invocation ->
                "epoch-0".equals(currentEpoch.get())
                        ? new SessionIdentity(7L, "epoch-0") : null);
        when(userMapper.findStatusById(7L)).thenAnswer(invocation -> {
            // 状态查询与首次解析之间穿插：停用提交 → 撤销 → 重新启用 → 新登录。
            currentEpoch.set("epoch-2");
            return new CurrentUserStatusRow("u", null, 1);
        });

        MockHttpServletRequest request = request("/api/auth/me", "Bearer old-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean continued = new AtomicBoolean(false);

        filter.doFilter(request, response, (req, res) -> continued.set(true));

        assertEquals(401, response.getStatus(), "旧会话不得凭重新启用后的状态放行");
        assertFalse(continued.get(), "旧会话不得放行到后续过滤器");
        assertNull(request.getAttribute(CurrentUserProvider.REQUEST_ATTRIBUTE));
        assertNull(request.getAttribute(CurrentUserProvider.TOKEN_ATTRIBUTE));
        verify(sessions, times(2)).resolveIdentity("old-token");
    }

    @Test
    void dependencyFailureReturnsServerErrorInsteadOfPassingThrough() throws Exception {
        when(sessions.resolveIdentity("opaque-token"))
                .thenThrow(new IllegalStateException("redis unavailable"));
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean continued = new AtomicBoolean(false);
        filter.doFilter(request("/api/auth/me", "Bearer opaque-token"), response,
                (req, res) -> continued.set(true));
        assertEquals(500, response.getStatus());
        assertEquals(500, body(response).get("code"));
        assertFalse(continued.get(), "旧会话不得放行到后续过滤器");
    }

    /** 第二次（状态确认后）解析失败也必须拒绝：复核不能成为绕过认证的缺口。 */
    @Test
    void reconfirmFailureReturnsServerErrorInsteadOfPassingThrough() throws Exception {
        when(sessions.resolveIdentity("reconfirm-token"))
                .thenReturn(new SessionIdentity(7L, "epoch-0"))
                .thenThrow(new IllegalStateException("redis unavailable"));
        when(userMapper.findStatusById(7L))
                .thenReturn(new CurrentUserStatusRow("u", null, 1));
        MockHttpServletRequest request = request("/api/auth/me", "Bearer reconfirm-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean continued = new AtomicBoolean(false);

        filter.doFilter(request, response, (req, res) -> continued.set(true));

        assertEquals(500, response.getStatus(), "复核解析异常不得静默放行");
        assertEquals(500, body(response).get("code"));
        assertFalse(continued.get(), "复核失败不得放行到后续过滤器");
        assertNull(request.getAttribute(CurrentUserProvider.REQUEST_ATTRIBUTE));
        assertNull(request.getAttribute(CurrentUserProvider.TOKEN_ATTRIBUTE));
        verify(sessions, times(2)).resolveIdentity("reconfirm-token");
    }
}
