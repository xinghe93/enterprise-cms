package com.xinghe.cms.user.controller;

import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xinghe.cms.security.AuthSessionService;
import com.xinghe.cms.security.BearerTokenAuthenticationFilter;
import com.xinghe.cms.user.dto.LoginRequest;
import com.xinghe.cms.user.mapper.UserAuthMapper;
import com.xinghe.cms.user.mapper.row.CurrentUserStatusRow;
import com.xinghe.cms.user.service.AuthService;
import com.xinghe.cms.user.vo.LoginResultVO;
import com.xinghe.cms.user.vo.UserVO;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * 登录接口的 HTTP 层回归：走真实 DispatcherServlet + Boot 配置的 Jackson 转换器 +
 * 公共异常处理器，不用 standaloneSetup，否则测不到实际 MVC 的反序列化配置。
 *
 * <p>覆盖契约 8.1：未知字段、未知查询参数、非法字段类型返回 400 且不进入登录服务；
 * 合法字符串仍可登录，密码按契约不 trim。</p>
 */
@SpringBootTest
class AuthControllerHttpTests {

    @Autowired
    private WebApplicationContext context;

    @MockitoBean
    private AuthService authService;

    @MockitoBean
    private AuthSessionService sessions;

    @MockitoBean
    private UserAuthMapper userAuthMapper;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean(BearerTokenAuthenticationFilter.class))
                .build();
    }

    @Test
    void unknownJsonFieldReturns400WithoutEnteringLoginService() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"editor_demo\",\"password\":\"pw\",\"isAdmin\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("请求参数错误"))
                .andExpect(jsonPath("$.data").value(nullValue()));

        verifyNoInteractions(authService);
    }

    @Test
    void unknownQueryParamReturns400WithoutEnteringLoginService() throws Exception {
        mockMvc.perform(post("/api/auth/login?unexpected=1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"editor_demo\",\"password\":\"pw\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("未知查询参数: unexpected"))
                .andExpect(jsonPath("$.data").value(nullValue()));

        verifyNoInteractions(authService);
    }

    @ParameterizedTest
    @CsvSource({"GET, /api/auth/me", "POST, /api/auth/logout"})
    void protectedAuthRoutesRejectUnknownQueryParamsBeforeCallingService(String method, String path) throws Exception {
        stubActiveSession();

        mockMvc.perform(request(HttpMethod.valueOf(method), path)
                        .servletPath(path)
                        .header("Authorization", "Bearer opaque-token")
                        .param("unexpected", "1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("未知查询参数: unexpected"));

        verifyNoInteractions(authService);
    }

    @Test
    void protectedAuthRoutesStillUseTheAuthenticatedIdentityAndToken() throws Exception {
        stubActiveSession();
        when(authService.currentUser(7L)).thenReturn(new UserVO("7", "editor_demo", "演示编辑",
                null, null, 1, List.of(), null, null));

        mockMvc.perform(get("/api/auth/me").servletPath("/api/auth/me")
                        .header("Authorization", "Bearer opaque-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value("7"));
        mockMvc.perform(post("/api/auth/logout").servletPath("/api/auth/logout")
                        .header("Authorization", "Bearer opaque-token"))
                .andExpect(status().isOk());

        verify(authService).currentUser(7L);
        verify(authService).logout("opaque-token");
    }

    private void stubActiveSession() {
        when(sessions.resolveIdentity("opaque-token"))
                .thenReturn(new AuthSessionService.SessionIdentity(7L, "epoch"));
        when(userAuthMapper.findStatusById(7L))
                .thenReturn(new CurrentUserStatusRow("editor_demo", null, 1));
    }

    /** 复审③：数字 / 布尔 / null / 数组 / 对象 / 缺字段一律 400，登录服务不得被调用。 */
    @Test
    void invalidFieldTypesReturn400WithoutEnteringLoginService() throws Exception {
        List<String> invalidBodies = List.of(
                "{\"username\":123,\"password\":\"pw\"}",
                "{\"username\":true,\"password\":\"pw\"}",
                "{\"username\":null,\"password\":\"pw\"}",
                "{\"username\":[\"editor_demo\"],\"password\":\"pw\"}",
                "{\"username\":{},\"password\":\"pw\"}",
                "{\"username\":\"editor_demo\",\"password\":123}",
                "{\"username\":\"editor_demo\",\"password\":false}",
                "{\"username\":\"editor_demo\",\"password\":null}",
                "{\"username\":\"editor_demo\",\"password\":[\"pw\"]}",
                "{\"username\":\"editor_demo\",\"password\":{}}",
                "{\"username\":\"editor_demo\"}");

        for (String body : invalidBodies) {
            mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(400))
                    .andExpect(jsonPath("$.data").value(nullValue()));
            // 只要有任何一次请求进入了 Service，这里就会失败。
            verifyNoInteractions(authService);
        }
    }

    @Test
    void wellFormedLoginBodyStillReachesService() throws Exception {
        when(authService.login(any(LoginRequest.class))).thenReturn(
                new LoginResultVO("opaque-token", "Bearer", 7200L,
                        new UserVO("7", "editor_demo", "演示编辑", null, null, 1,
                                List.of(), null, null)));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"editor_demo\",\"password\":\"pw\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.accessToken").value("opaque-token"));
    }

    /** 契约 8.1：密码不 trim，带首尾空白的字符串必须原样传入登录服务。 */
    @Test
    void passwordWithSurroundingWhitespaceIsPassedThroughUnchanged() throws Exception {
        when(authService.login(any(LoginRequest.class))).thenReturn(
                new LoginResultVO("opaque-token", "Bearer", 7200L,
                        new UserVO("7", "editor_demo", "演示编辑", null, null, 1,
                                List.of(), null, null)));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"editor_demo\",\"password\":\"  pw  \"}"))
                .andExpect(status().isOk());

        ArgumentCaptor<LoginRequest> request = ArgumentCaptor.forClass(LoginRequest.class);
        verify(authService).login(request.capture());
        assertEquals("editor_demo", request.getValue().username());
        assertEquals("  pw  ", request.getValue().password(), "密码首尾空白不得被 trim 或截断");
    }

    /**
     * 登录与创建入口共用同一 Unicode 长度口径（老师复审 P1）：创建接口（AdminJson）
     * 按 Unicode 代码点计数，允许保存 64 个「𠮷」组成的用户名（raw.length()=128）
     * 与 128 个代码点的密码（raw.length()=256）；登录若继续按 @Size 的 UTF-16
     * 计数会在验证凭据前返回 400。登录必须按代码点校验并原样传入登录服务；
     * 65 / 129 个代码点则仍在绑定阶段 400。
     */
    @Test
    void loginCountsLengthByCodePointsConsistentWithCreateContract() throws Exception {
        String username = "\uD842\uDFB7".repeat(64);
        String password = "\uD842\uDFB7".repeat(128);
        when(authService.login(any(LoginRequest.class))).thenReturn(
                new LoginResultVO("opaque-token", "Bearer", 7200L,
                        new UserVO("7", username, "演示编辑", null, null, 1,
                                List.of(), null, null)));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.accessToken").value("opaque-token"));

        ArgumentCaptor<LoginRequest> request = ArgumentCaptor.forClass(LoginRequest.class);
        verify(authService).login(request.capture());
        assertEquals(username, request.getValue().username(), "64 个代码点用户名必须原样传入");
        assertEquals(password, request.getValue().password(), "128 个代码点密码必须原样传入");

        // 65 个代码点的用户名 / 129 个代码点的密码超出创建契约上限，登录同样拒绝。
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + "\uD842\uDFB7".repeat(65)
                                + "\",\"password\":\"12345678\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"editor_demo\",\"password\":\""
                                + "\uD842\uDFB7".repeat(129) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        // 超限请求不得进入登录服务：合法调用只有上面的一次。
        verify(authService, times(1)).login(any(LoginRequest.class));
    }
}
