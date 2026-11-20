package com.xinghe.cms.user.controller;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xinghe.cms.common.contract.AuthorizationSnapshot;
import com.xinghe.cms.common.exception.BusinessException;
import com.xinghe.cms.common.response.ResultCode;
import com.xinghe.cms.log.service.AuditLogWriter;
import com.xinghe.cms.permission.service.AuthorizationQueryService;
import com.xinghe.cms.security.AuthSessionService;
import com.xinghe.cms.security.BearerTokenAuthenticationFilter;
import com.xinghe.cms.user.mapper.UserAuthMapper;
import com.xinghe.cms.user.mapper.row.CurrentUserStatusRow;
import com.xinghe.cms.user.service.UserStatusService;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * 启停接口的 HTTP 层回归：走真实 DispatcherServlet、Boot 的 Jackson 配置、
 * 方法参数校验、公共异常处理器与 A 的认证过滤器。
 *
 * <p>身份链路用打桩的会话服务放行一个有效 Token，业务 Service 也被打桩，
 * 因此无需真实 MySQL/Redis；覆盖契约 8.1 的 400、认证 401、资源 404 与冲突 409。</p>
 */
@SpringBootTest
class UserAdminControllerHttpTests {

    private static final String TOKEN = "good-token";
    private static final Set<String> ALL_PERMISSIONS = Set.of(
            "user:list", "user:create", "user:read", "user:update", "user:status",
            "user:assign-role");

    @Autowired
    private WebApplicationContext context;

    @MockitoBean
    private UserStatusService userStatusService;
    @MockitoBean
    private AuthSessionService sessions;
    @MockitoBean
    private UserAuthMapper userAuthMapper;
    /** 权限 AOP / 审计 AOP 都要查库，本机测试无 MySQL，按契约替换为可控桩。 */
    @MockitoBean
    private AuthorizationQueryService authorizationQueryService;
    @MockitoBean
    private AuditLogWriter auditLogWriter;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        when(sessions.resolveIdentity(TOKEN))
                .thenReturn(new AuthSessionService.SessionIdentity(9L, "epoch"));
        when(userAuthMapper.findStatusById(9L))
                .thenReturn(new CurrentUserStatusRow("admin", null, 1));
        when(authorizationQueryService.getForUser(9L))
                .thenReturn(new AuthorizationSnapshot(Set.of(), ALL_PERMISSIONS));
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean(BearerTokenAuthenticationFilter.class))
                .build();
    }

    @Test
    void disableReturns200WithNullData() throws Exception {
        performPut("7", "{\"status\":0}", true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("操作成功"))
                .andExpect(jsonPath("$.data").value(nullValue()));

        verify(userStatusService).changeStatus(7L, 0);
    }

    @Test
    void enableReturns200WithNullData() throws Exception {
        performPut("7", "{\"status\":1}", true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(nullValue()));

        verify(userStatusService).changeStatus(7L, 1);
    }

    /** OpenAPI Id schema：十进制正整数字符串，无前导零，最长 19 位且不超 Long.MAX_VALUE。 */
    @Test
    void invalidIdsReturn400WithoutEnteringService() throws Exception {
        for (String id : List.of("0", "01", "abc", "1.0", "-1", "99999999999999999999")) {
            performPut(id, "{\"status\":0}", true)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(400))
                    .andExpect(jsonPath("$.data").value(nullValue()));
        }
        // 19 位但超过 Long.MAX_VALUE：pattern 通过，parseLong 必须转成 400 而不是 500。
        performPut("9999999999999999999", "{\"status\":0}", true)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.data").value(nullValue()));

        verifyNoInteractions(userStatusService);
    }

    /** 契约 8.1：status 必须是 JSON 整数 0/1；未知字段、null、字符串、布尔等一律 400。 */
    @Test
    void invalidBodiesReturn400WithoutEnteringService() throws Exception {
        List<String> invalidBodies = List.of(
                "{\"status\":\"0\"}",
                "{\"status\":true}",
                "{\"status\":false}",
                "{\"status\":null}",
                "{\"status\":1.5}",
                "{\"status\":2}",
                "{\"status\":-1}",
                "{\"status\":999999999999999999999}",
                "{\"status\":{}}",
                "{\"status\":[]}",
                "{}",
                "{\"status\":0,\"isAdmin\":true}",
                "[]",
                "null");

        for (String body : invalidBodies) {
            performPut("7", body, true)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(400))
                    .andExpect(jsonPath("$.data").value(nullValue()));
        }

        verifyNoInteractions(userStatusService);
    }

    /** 受保护接口在 MVC 参数绑定前由过滤器拒绝，业务服务不得被调用。 */
    @Test
    void missingTokenReturns401WithoutEnteringService() throws Exception {
        performPut("7", "{\"status\":0}", false)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));

        verifyNoInteractions(userStatusService);
    }

    /** P1 权限回归：普通员工无任何功能权限时调用启停接口必须 403，业务服务不得执行。 */
    @Test
    void missingPermissionReturns403WithoutEnteringService() throws Exception {
        when(authorizationQueryService.getForUser(9L))
                .thenReturn(new AuthorizationSnapshot(Set.of(), Set.of()));

        performPut("7", "{\"status\":0}", true)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));

        verifyNoInteractions(userStatusService);
    }

    @Test
    void serviceNotFoundMapsTo404() throws Exception {
        doThrow(new BusinessException(ResultCode.NOT_FOUND, "员工不存在"))
                .when(userStatusService).changeStatus(7L, 0);

        performPut("7", "{\"status\":0}", true)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.data").value(nullValue()));
    }

    @Test
    void serviceConflictMapsTo409() throws Exception {
        doThrow(new BusinessException(ResultCode.CONFLICT, "员工状态已变更，请重试"))
                .when(userStatusService).changeStatus(7L, 0);

        performPut("7", "{\"status\":0}", true)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.data").value(nullValue()));
    }

    private ResultActions performPut(String id, String body, boolean withToken) throws Exception {
        // MockMvc 默认 servletPath 为空，过滤器的 shouldNotFilter 会误判成非 API 请求而跳过。
        // 真实容器里 DispatcherServlet 映射为 "/" 时 servletPath 就是完整路径；这里补齐它，
        // 但不要设置 pathInfo（会让 Spring 把查找路径截成 /admin/... 而 404）。
        String path = "/api/admin/users/" + id + "/status";
        var request = put(path)
                .servletPath(path)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
        if (withToken) {
            request.header("Authorization", "Bearer " + TOKEN);
        }
        return mockMvc.perform(request);
    }
}
