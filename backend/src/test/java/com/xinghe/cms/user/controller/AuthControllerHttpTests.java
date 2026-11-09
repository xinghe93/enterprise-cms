package com.xinghe.cms.user.controller;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * 登录接口的 HTTP 层回归：契约 8.1 要求未知 JSON 字段返回 400。
 * 走真实 DispatcherServlet + Boot 配置的 Jackson 转换器 + 公共异常处理器，
 * 不用 standaloneSetup，否则测不到 application.yaml 的全局反序列化设置。
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
}
