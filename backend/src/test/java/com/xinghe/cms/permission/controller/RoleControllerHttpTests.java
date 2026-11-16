package com.xinghe.cms.permission.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xinghe.cms.common.contract.AuthorizationSnapshot;
import com.xinghe.cms.common.contract.CurrentUser;
import com.xinghe.cms.common.request.PageQuery;
import com.xinghe.cms.common.response.PageResult;
import com.xinghe.cms.permission.response.RoleView;
import com.xinghe.cms.permission.service.AuthorizationQueryService;
import com.xinghe.cms.permission.service.RoleQueryService;
import com.xinghe.cms.security.CurrentUserProvider;
import java.util.List;
import java.util.Set;
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

    /** 复审 P2：status=0x1 会被标量绑定按十六进制解析成 1，必须按原始值拒绝。 */
    @Test
    void hexVariantStatusParamReturns400() throws Exception {
        mockMvc.perform(get("/api/admin/roles")
                        .param("current", "1")
                        .param("size", "10")
                        .param("status", "0x1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        verifyNoInteractions(roleQueryService);
    }

    /** 复审 P2：重复传参 status=1&status=0 会被标量绑定静默取首值，必须 400。 */
    @Test
    void duplicatedStatusParamReturns400() throws Exception {
        mockMvc.perform(get("/api/admin/roles")
                        .param("current", "1")
                        .param("size", "10")
                        .param("status", "1")
                        .param("status", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        verifyNoInteractions(roleQueryService);
    }

    /**
     * 复审 P3 复现：current=1%202&size=3%204 会被 Spring 数字绑定忽略空白解析为
     * 第 12 页、每页 34 条，必须按原始取值 400 拒绝且不进入服务。
     */
    @Test
    void whitespacePaddedPaginationParamsReturn400() throws Exception {
        mockMvc.perform(get("/api/admin/roles")
                        .param("current", "1 2")
                        .param("size", "3 4"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.data").value(nullValue()));

        verifyNoInteractions(roleQueryService);
    }

    /**
     * 复审 P2：current/size 未传时保留默认值——且服务必须“实际收到”第 1 页、
     * 每页 10 条（用 ArgumentCaptor 断言构造出的 PageQuery，而非只看响应）。
     */
    @Test
    void paginationParamsOmittedFallBackToDefaults() throws Exception {
        when(roleQueryService.page(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.isNull()))
                .thenReturn(new PageResult<>(List.of(), 0L, 1L, 10L));

        mockMvc.perform(get("/api/admin/roles"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        ArgumentCaptor<PageQuery> captor = ArgumentCaptor.forClass(PageQuery.class);
        verify(roleQueryService).page(captor.capture(),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.isNull());
        assertThat(captor.getValue().getCurrent()).isEqualTo(1L);
        assertThat(captor.getValue().getSize()).isEqualTo(10L);
    }

    /**
     * 复审 P2：分页值只能来自查询参数——即使带同名请求头（current/size），
     * 也不得影响绑定结果，服务仍收到默认第 1 页、每页 10 条。
     */
    @Test
    void sameNamedHeadersCannotOverridePaginationDefaults() throws Exception {
        when(roleQueryService.page(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.isNull()))
                .thenReturn(new PageResult<>(List.of(), 0L, 1L, 10L));

        mockMvc.perform(get("/api/admin/roles")
                        .header("current", "7")
                        .header("size", "99"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        ArgumentCaptor<PageQuery> captor = ArgumentCaptor.forClass(PageQuery.class);
        verify(roleQueryService).page(captor.capture(),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.isNull());
        assertThat(captor.getValue().getCurrent()).isEqualTo(1L);
        assertThat(captor.getValue().getSize()).isEqualTo(10L);
    }

    /** 详情接口不接受任何查询参数。 */
    @Test
    void detailRejectsUnknownQueryParam() throws Exception {
        mockMvc.perform(get("/api/admin/roles/37").param("foo", "1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        verifyNoInteractions(roleQueryService);
    }

    /** 合法请求仍正常到达服务，且服务收到的是按查询参数解析出的分页值。 */
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

        ArgumentCaptor<PageQuery> captor = ArgumentCaptor.forClass(PageQuery.class);
        verify(roleQueryService).page(captor.capture(),
                org.mockito.ArgumentMatchers.eq("EDITOR"),
                org.mockito.ArgumentMatchers.eq(1));
        assertThat(captor.getValue().getCurrent()).isEqualTo(1L);
        assertThat(captor.getValue().getSize()).isEqualTo(10L);
    }
}
