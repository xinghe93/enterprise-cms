package com.xinghe.cms.user.controller;

import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import com.xinghe.cms.user.dto.UserCreateRequest;
import com.xinghe.cms.user.dto.UserListQuery;
import com.xinghe.cms.user.dto.UserRolesUpdateRequest;
import com.xinghe.cms.user.dto.UserUpdateRequest;
import com.xinghe.cms.user.mapper.UserAuthMapper;
import com.xinghe.cms.user.mapper.row.CurrentUserStatusRow;
import com.xinghe.cms.user.service.UserAdminService;
import com.xinghe.cms.user.service.UserStatusService;
import com.xinghe.cms.user.vo.UserPageVO;
import com.xinghe.cms.user.vo.UserVO;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * 员工 5 个新增接口的 HTTP 层回归：真实 DispatcherServlet + Boot 的 Jackson 配置、
 * 公共异常处理器与 A 的认证过滤器，业务服务打桩。
 *
 * <p>覆盖契约 8.1 的类型/未知字段/必填校验、8.2 的查询参数校验、ID 格式、
 * 认证 401 与业务 404/409。</p>
 */
@SpringBootTest
class UserAdminEndpointsHttpTests {

    private static final String TOKEN = "good-token";
    private static final String USERS = "/api/admin/users";
    private static final Set<String> ALL_PERMISSIONS = Set.of(
            "user:list", "user:create", "user:read", "user:update", "user:status",
            "user:assign-role");

    @Autowired
    private WebApplicationContext context;

    @MockitoBean
    private UserAdminService userAdminService;
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

    private UserVO user() {
        return new UserVO("7", "editor_demo", "演示编辑", null, null, 1,
                List.of(), "2026-11-02T16:00:00.000", "2026-11-02T16:00:00.000");
    }

    /** MockMvc 默认 servletPath 为空，认证过滤器会误判为「非 API 请求」而跳过。 */
    private ResultActions perform(MockHttpServletRequestBuilder request, String path)
            throws Exception {
        return mockMvc.perform(request.servletPath(path)
                .header("Authorization", "Bearer " + TOKEN));
    }

    @Test
    void createUserReturnsFullUserAndNeverEchoesPassword() throws Exception {
        when(userAdminService.createUser(any(UserCreateRequest.class))).thenReturn(user());

        perform(post(USERS).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"editor_demo\",\"password\":\"12345678\","
                                + "\"realName\":\"演示编辑\"}"), USERS)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").value("7"))
                .andExpect(jsonPath("$.data.username").value("editor_demo"))
                .andExpect(jsonPath("$.data.roles").isArray())
                .andExpect(jsonPath("$.data.password").doesNotExist())
                .andExpect(jsonPath("$.data.passwordHash").doesNotExist());
    }

    @Test
    void createUserRejectsInvalidBodiesBeforeEnteringService() throws Exception {
        List<String> invalid = List.of(
                "{}",
                "[]",
                "null",
                "{\"username\":\"u\",\"password\":\"12345678\",\"realName\":\"n\",\"isAdmin\":true}",
                "{\"username\":1,\"password\":\"12345678\",\"realName\":\"n\"}",
                "{\"username\":\"u\",\"password\":12345678,\"realName\":\"n\"}",
                "{\"username\":\"u\",\"password\":\"12345678\",\"realName\":null}",
                "{\"username\":\"u\",\"password\":\"1234567\",\"realName\":\"n\"}",
                "{\"username\":\"   \",\"password\":\"12345678\",\"realName\":\"n\"}",
                "{\"username\":\"u\",\"password\":\"12345678\",\"realName\":\"n\",\"departmentId\":1}",
                "{\"username\":\"u\",\"password\":\"12345678\",\"realName\":\"n\",\"departmentId\":\"0\"}",
                "{\"username\":\"u\",\"password\":\"12345678\",\"realName\":\"n\",\"roleIds\":[]}");
        for (String body : invalid) {
            perform(post(USERS).contentType(MediaType.APPLICATION_JSON).content(body), USERS)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(400));
        }
        verifyNoInteractions(userAdminService);
    }

    @Test
    void updateUserRequiresBothFieldsAndRejectsExtras() throws Exception {
        String path = USERS + "/7";
        for (String body : List.of(
                "{\"realName\":\"n\"}",
                "{\"departmentId\":null}",
                "{\"realName\":\"n\",\"departmentId\":null,\"status\":1}",
                "{\"realName\":\"n\",\"departmentId\":\"01\"}",
                "{}")) {
            perform(put(path).contentType(MediaType.APPLICATION_JSON).content(body), path)
                    .andExpect(status().isBadRequest());
        }
        when(userAdminService.updateUser(anyLong(), any(UserUpdateRequest.class))).thenReturn(user());
        perform(put(path).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"realName\":\"演示编辑\",\"departmentId\":null}"), path)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value("7"));
        verify(userAdminService).updateUser(7L, new UserUpdateRequest("演示编辑", null));
    }

    @Test
    void rolesRejectDuplicatesBadIdsAndUnknownFields() throws Exception {
        String path = USERS + "/7/roles";
        StringBuilder tooMany = new StringBuilder("[");
        for (int i = 1; i <= 101; i++) {
            tooMany.append(i == 1 ? "" : ",").append('"').append(i).append('"');
        }
        tooMany.append(']');
        for (String body : List.of(
                "{}",
                "{\"roleIds\":null}",
                "{\"roleIds\":\"1\"}",
                "{\"roleIds\":[1]}",
                "{\"roleIds\":[\"1\",\"1\"]}",
                "{\"roleIds\":[\"0\"]}",
                "{\"roleIds\":[\"9999999999999999999\"]}",
                "{\"roleIds\":[],\"extra\":1}",
                "{\"roleIds\":" + tooMany + "}")) {
            perform(put(path).contentType(MediaType.APPLICATION_JSON).content(body), path)
                    .andExpect(status().isBadRequest());
        }
        perform(put(path).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roleIds\":[]}"), path)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(nullValue()));
        verify(userAdminService).replaceRoles(7L, new UserRolesUpdateRequest(List.of()));
    }

    @Test
    void invalidIdsReturn400BeforeEnteringService() throws Exception {
        for (String id : List.of("0", "01", "abc", "1.0", "-1", "99999999999999999999")) {
            String detail = USERS + "/" + id;
            perform(get(detail), detail).andExpect(status().isBadRequest());
            String roles = detail + "/roles";
            perform(put(roles).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"roleIds\":[]}"), roles).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(userAdminService);
    }

    @Test
    void unknownOrInvalidQueryParamsReturn400() throws Exception {
        String[][] invalid = {
                {"foo", "1"}, {"size", "0"}, {"size", "101"}, {"size", "abc"},
                {"current", "0"}, {"current", "01"}, {"status", "2"},
                {"status", "true"}, {"departmentId", "01"},
                {"keyword", "x".repeat(101)},
        };
        for (String[] param : invalid) {
            perform(get(USERS).param(param[0], param[1]), USERS)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(400));
        }
        // 19 位页码 * 100 溢出 LIMIT/OFFSET，必须在 Controller 变 400。
        perform(get(USERS).param("current", "9999999999999999999").param("size", "100"), USERS)
                .andExpect(status().isBadRequest());
        verifyNoInteractions(userAdminService);
    }

    @Test
    void listUsersPassesParsedQueryAndReturnsPage() throws Exception {
        when(userAdminService.listUsers(any(UserListQuery.class)))
                .thenReturn(new UserPageVO(List.of(user()), 1L, 2L, 10L));

        perform(get(USERS).param("current", "2").param("size", "10")
                        .param("keyword", "  editor  ")
                        .param("departmentId", "3").param("status", "1"), USERS)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.current").value(2))
                .andExpect(jsonPath("$.data.records[0].id").value("7"));

        ArgumentCaptor<UserListQuery> query = ArgumentCaptor.forClass(UserListQuery.class);
        verify(userAdminService).listUsers(query.capture());
        assertEquals(new UserListQuery(2L, 10L, "editor", 3L, 1), query.getValue());
    }

    @Test
    void missingTokenReturns401BeforeEnteringService() throws Exception {
        mockMvc.perform(get(USERS).servletPath(USERS))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
        verifyNoInteractions(userAdminService);
    }

    /** P1 权限回归：普通员工无任何功能权限时调用员工接口必须 403，业务服务不得执行。 */
    @Test
    void missingPermissionReturns403WithoutEnteringService() throws Exception {
        when(authorizationQueryService.getForUser(9L))
                .thenReturn(new AuthorizationSnapshot(Set.of(), Set.of()));

        perform(get(USERS), USERS)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));

        verifyNoInteractions(userAdminService);
    }

    @Test
    void getUserMapsNotFoundTo404AndCreateConflictTo409() throws Exception {
        String detail = USERS + "/7";
        doThrow(new BusinessException(ResultCode.NOT_FOUND, "员工不存在"))
                .when(userAdminService).getUser(7L);
        perform(get(detail), detail)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));

        doThrow(new BusinessException(ResultCode.CONFLICT, "登录账号已存在"))
                .when(userAdminService).createUser(any(UserCreateRequest.class));
        perform(post(USERS).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"dup\",\"password\":\"12345678\",\"realName\":\"姓名\"}"),
                USERS)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409));
    }
}
