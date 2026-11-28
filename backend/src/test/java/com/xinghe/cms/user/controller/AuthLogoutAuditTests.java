package com.xinghe.cms.user.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xinghe.cms.log.entity.OperationLog;
import com.xinghe.cms.log.service.AuditLogWriter;
import com.xinghe.cms.security.AuthSessionService;
import com.xinghe.cms.security.BearerTokenAuthenticationFilter;
import com.xinghe.cms.user.mapper.UserAuthMapper;
import com.xinghe.cms.user.mapper.row.CurrentUserStatusRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * 退出登录的审计切面端到端回归：不打桩 AuthService，验证 Spring AOP 代理在
 * POST /api/auth/logout 成功与 Redis 异常时均记录 auth.logout 元数据且不记录 Token。
 */
@SpringBootTest
class AuthLogoutAuditTests {

    private static final String TOKEN = "opaque-secret-token";

    @Autowired
    private WebApplicationContext context;

    @MockitoBean
    private AuthSessionService sessions;

    @MockitoBean
    private UserAuthMapper userAuthMapper;

    @MockitoBean
    private AuditLogWriter auditLogWriter;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        when(sessions.resolveIdentity(TOKEN))
                .thenReturn(new AuthSessionService.SessionIdentity(7L, "epoch-1"));
        when(userAuthMapper.findStatusById(7L))
                .thenReturn(new CurrentUserStatusRow("editor_demo", null, 1));
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean(BearerTokenAuthenticationFilter.class))
                .build();
    }

    @Test
    void logoutSuccessRecordsAuthLogoutAuditWithoutToken() throws Exception {
        mockMvc.perform(post("/api/auth/logout")
                        .servletPath("/api/auth/logout")
                        .header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        verify(sessions).revoke(TOKEN);
        ArgumentCaptor<OperationLog> captor = ArgumentCaptor.forClass(OperationLog.class);
        verify(auditLogWriter).write(captor.capture());

        OperationLog entry = captor.getValue();
        assertThat(entry.getUserId()).isEqualTo(7L);
        assertThat(entry.getOperation()).isEqualTo("auth.logout");
        assertThat(entry.getRequestUri()).isEqualTo("/api/auth/logout");
        assertThat(entry.getHttpMethod()).isEqualTo("POST");
        assertThat(entry.getResult()).isEqualTo("SUCCESS");
        assertThat(entry.getDurationMs()).isGreaterThanOrEqualTo(0L);
        assertThat(entry.getRequestUri()).doesNotContain(TOKEN);
    }

    @Test
    void logoutRevokeFailureRecordsFailureAuditAndReturns500() throws Exception {
        doThrow(new IllegalStateException("Redis unavailable"))
                .when(sessions).revoke(TOKEN);

        mockMvc.perform(post("/api/auth/logout")
                        .servletPath("/api/auth/logout")
                        .header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(500));

        ArgumentCaptor<OperationLog> captor = ArgumentCaptor.forClass(OperationLog.class);
        verify(auditLogWriter).write(captor.capture());

        OperationLog entry = captor.getValue();
        assertThat(entry.getUserId()).isEqualTo(7L);
        assertThat(entry.getOperation()).isEqualTo("auth.logout");
        assertThat(entry.getResult()).isEqualTo("FAILURE");
    }
}
