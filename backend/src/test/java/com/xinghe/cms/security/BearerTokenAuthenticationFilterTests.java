package com.xinghe.cms.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.xinghe.cms.common.contract.CurrentUser;
import com.xinghe.cms.user.mapper.UserAuthMapper;
import com.xinghe.cms.user.mapper.row.CurrentUserStatusRow;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
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
        assertTrue(!continued.get());
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
        assertTrue(!continued.get());
    }

    @Test
    void validSessionWritesVerifiedIdentityWithoutClientUserId() throws Exception {
        when(sessions.resolve("opaque-token")).thenReturn(7L);
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
    }

    @Test
    void revokedTokenIsUnauthorized() throws Exception {
        when(sessions.resolve("revoked")).thenReturn(null);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request("/api/auth/logout", "Bearer revoked"), response, (req, res) -> { });
        assertEquals(401, response.getStatus());
    }

    @Test
    void disabledUserIsRejectedAndAllOldSessionsAreInvalidated() throws Exception {
        when(sessions.resolve("opaque-token")).thenReturn(7L);
        when(userMapper.findStatusById(7L)).thenReturn(new CurrentUserStatusRow("u", null, 0));
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request("/api/auth/me", "Bearer opaque-token"), response, (req, res) -> { });
        assertEquals(401, response.getStatus());
        verify(sessions).revokeAllForUser(7L);
    }

    @Test
    void dependencyFailureReturnsServerErrorInsteadOfPassingThrough() throws Exception {
        when(sessions.resolve("opaque-token")).thenThrow(new IllegalStateException("redis unavailable"));
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean continued = new AtomicBoolean(false);
        filter.doFilter(request("/api/auth/me", "Bearer opaque-token"), response,
                (req, res) -> continued.set(true));
        assertEquals(500, response.getStatus());
        assertEquals(500, body(response).get("code"));
        assertTrue(!continued.get());
    }
}
