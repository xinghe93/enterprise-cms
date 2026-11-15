package com.xinghe.cms.permission.controller;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xinghe.cms.common.contract.AuthorizationSnapshot;
import com.xinghe.cms.common.contract.CurrentUser;
import com.xinghe.cms.common.response.PageResult;
import com.xinghe.cms.permission.response.RoleView;
import com.xinghe.cms.permission.service.AuthorizationQueryService;
import com.xinghe.cms.permission.service.RoleQueryService;
import com.xinghe.cms.security.CurrentUserProvider;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * 角色列表/详情接口的 HTTP 层回归：走真实 DispatcherServlet + 公共异常处理器，
 * 复刻评审 bot 对 PR #23 P3 的复现（走实际 MVC 参数绑定，查询服务用 mock）。
 *
 * <p>覆盖契约 8.x：未知查询参数、空值枚举参数一律 400 且不进入查询服务；
 * 合法请求仍正常到达服务。鉴权切面通过 mock CurrentUserProvider 与
 * AuthorizationQueryService 放行，不在本类关注范围内。</p>
 */
@SpringBootTest
class RoleControllerHttpTests {

    @Autowired
    private WebApplicationContext context;

    @MockitoBean
    private RoleQueryService roleQueryService;

    @MockitoBean
    private CurrentUserProvider currentUserProvider;

    @MockitoBean
    private AuthorizationQueryService authorizationQueryService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        when(currentUserProvider.requireCurrentUser())
                .thenReturn(new CurrentUser(37L, "admin01", null));
        when(authorizationQueryService.getForUser(37L))
                .thenReturn(new AuthorizationSnapshot(Set.of("ADMIN"), Set.of("role:list", "role:read")));

        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    /** P3 复现①：拼错的参数名不得被静默忽略，必须 400 且不进入服务。 */
    @Test
    void misspelledParamReturns400WithoutEnteringService() throws Exception {
        mockMvc.perform(get("/api/admin/roles")
                        .param("current", "1")
                        .param("size", "10")
                        .param("staus", "1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.data").value(nullValue()));

        verifyNoInteractions(roleQueryService);
    }

    /** P3 复现②：status= 空值不得按未传处理，必须 400。 */
    @Test
    void blankStatusParamReturns400() throws Exception {
        mockMvc.perform(get("/api/admin/roles")
                        .param("current", "1")
                        .param("size", "10")
                        .param("status", ""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        verifyNoInteractions(roleQueryService);
    }

    /** 详情接口不接受任何查询参数。 */
    @Test
    void detailRejectsUnknownQueryParam() throws Exception {
        mockMvc.perform(get("/api/admin/roles/37").param("foo", "1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        verifyNoInteractions(roleQueryService);
    }

    /** 合法请求仍正常到达服务。 */
    @Test
    void validRequestStillReachesService() throws Exception {
        when(roleQueryService.page(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq("EDITOR"),
                org.mockito.ArgumentMatchers.eq(1)))
                .thenReturn(new PageResult<>(List.of(
                        new RoleView("37", "EDITOR", "编辑", 1,
                                java.time.LocalDateTime.of(2026, 11, 10, 9, 30),
                                java.time.LocalDateTime.of(2026, 11, 10, 9, 30)),
                        new RoleView("41", "REVIEWER", "审核员", 1,
                                java.time.LocalDateTime.of(2026, 11, 10, 10, 0),
                                java.time.LocalDateTime.of(2026, 11, 10, 10, 0))),
                        2L, 1L, 10L));

        mockMvc.perform(get("/api/admin/roles")
                        .param("current", "1")
                        .param("size", "10")
                        .param("keyword", "EDITOR")
                        .param("status", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.records.length()").value(2));

        verify(roleQueryService).page(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq("EDITOR"),
                org.mockito.ArgumentMatchers.eq(1));
    }
}
