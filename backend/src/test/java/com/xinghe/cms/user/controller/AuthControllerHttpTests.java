package com.xinghe.cms.user.controller;

import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xinghe.cms.security.BearerTokenAuthenticationFilter;
import com.xinghe.cms.user.dto.LoginRequest;
import com.xinghe.cms.user.service.AuthService;
import com.xinghe.cms.user.vo.LoginResultVO;
import com.xinghe.cms.user.vo.UserVO;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * 登录接口的 HTTP 层回归：走真实 DispatcherServlet + Boot 配置的 Jackson 转换器 +
 * 公共异常处理器，不用 standaloneSetup，否则测不到实际 MVC 的反序列化配置。
 *
 * <p>覆盖契约 8.1：未知字段、非法字段类型返回 400 且不进入登录服务；
 * 合法字符串仍可登录，密码按契约不 trim。</p>
 */
@SpringBootTest
class AuthControllerHttpTests {

    @Autowired
    private WebApplicationContext context;

    @MockitoBean
    private AuthService authService;

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
}
