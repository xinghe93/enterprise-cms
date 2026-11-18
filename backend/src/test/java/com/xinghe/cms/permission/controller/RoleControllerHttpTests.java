package com.xinghe.cms.permission.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
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
import com.xinghe.cms.common.contract.CurrentUser;
import com.xinghe.cms.common.exception.BusinessException;
import com.xinghe.cms.common.request.PageQuery;
import com.xinghe.cms.common.response.PageResult;
import com.xinghe.cms.common.response.ResultCode;
import com.xinghe.cms.permission.request.RoleCreateRequest;
import com.xinghe.cms.permission.request.RoleStatusRequest;
import com.xinghe.cms.permission.request.RoleUpdateRequest;
import com.xinghe.cms.permission.response.RoleView;
import com.xinghe.cms.permission.service.AuthorizationQueryService;
import com.xinghe.cms.permission.service.RoleAdminService;
import com.xinghe.cms.permission.service.RoleQueryService;
import com.xinghe.cms.security.CurrentUserProvider;
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
    private RoleAdminService roleAdminService;

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
                .thenReturn(new AuthorizationSnapshot(Set.of("ADMIN"),
                        Set.of("role:list", "role:read", "role:create", "role:update", "role:status")));

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

    /**
     * 复审 P2：current 是 OpenAPI int64——Long.MAX_VALUE 配合 size=1 偏移量不溢出，
     * 按契约返回 200 空页，服务收到完整还原的真实数值。
     */
    @Test
    void int64BoundaryCurrentWithSizeOneReturns200Empty() throws Exception {
        when(roleQueryService.page(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.isNull()))
                .thenReturn(new PageResult<>(List.of(), 0L, Long.MAX_VALUE, 1L));

        mockMvc.perform(get("/api/admin/roles")
                        .param("current", "9223372036854775807")
                        .param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.records.length()").value(0))
                .andExpect(jsonPath("$.data.total").value(0));

        ArgumentCaptor<PageQuery> captor = ArgumentCaptor.forClass(PageQuery.class);
        verify(roleQueryService).page(captor.capture(),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.isNull());
        assertThat(captor.getValue().getCurrent()).isEqualTo(Long.MAX_VALUE);
        assertThat(captor.getValue().getSize()).isEqualTo(1L);
    }

    /** 复审 P2：超出 int64 范围的取值（Long.MAX_VALUE + 1）在解析阶段返回 400。 */
    @Test
    void currentBeyondInt64RangeReturns400() throws Exception {
        mockMvc.perform(get("/api/admin/roles")
                        .param("current", "9999999999999999999")
                        .param("size", "1"))
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

    // ---------- 创建角色（POST /api/admin/roles） ----------

    /** 创建成功：返回 200 与完整 Role，服务收到请求体解析出的两个字段。 */
    @Test
    void createRoleReturnsFullView() throws Exception {
        when(roleAdminService.create(new RoleCreateRequest("CUSTOM_EDITOR", "自定义编辑")))
                .thenReturn(new RoleView("77", "CUSTOM_EDITOR", "自定义编辑", 1,
                        java.time.LocalDateTime.of(2026, 11, 17, 17, 40),
                        java.time.LocalDateTime.of(2026, 11, 17, 17, 40)));

        mockMvc.perform(post("/api/admin/roles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"roleCode":"CUSTOM_EDITOR","roleName":"自定义编辑"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").value("77"))
                .andExpect(jsonPath("$.data.roleCode").value("CUSTOM_EDITOR"))
                .andExpect(jsonPath("$.data.status").value(1))
                .andExpect(jsonPath("$.data.createdAt").value("2026-11-17T17:40:00.000"));

        verify(roleAdminService).create(new RoleCreateRequest("CUSTOM_EDITOR", "自定义编辑"));
    }

    /** 重复编码 409（服务抛 BusinessException(CONFLICT)，HTTP 与 code 一致）。 */
    @Test
    void duplicateRoleCodeReturns409() throws Exception {
        when(roleAdminService.create(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new BusinessException(ResultCode.CONFLICT, "角色编码已存在"));

        mockMvc.perform(post("/api/admin/roles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"roleCode":"EDITOR","roleName":"编辑"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.data").value(nullValue()));
    }

    /** 评审 P2：带首尾空白的重复编码由服务层规范化后判重，端到端返回 409。 */
    @Test
    void whitespacePaddedDuplicateRoleCodeReturns409() throws Exception {
        when(roleAdminService.create(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new BusinessException(ResultCode.CONFLICT, "角色编码已存在"));

        mockMvc.perform(post("/api/admin/roles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"roleCode":"  EDITOR  ","roleName":"编辑"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.data").value(nullValue()));

        org.mockito.ArgumentCaptor<RoleCreateRequest> captor =
                org.mockito.ArgumentCaptor.forClass(RoleCreateRequest.class);
        verify(roleAdminService).create(captor.capture());
        assertThat(captor.getValue().roleCode()).isEqualTo("  EDITOR  ");
    }

    /** 空白 roleCode 被 @NotBlank 拦截为 400，不进入服务。 */
    @Test
    void blankRoleCodeReturns400() throws Exception {
        mockMvc.perform(post("/api/admin/roles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"roleCode":"   ","roleName":"编辑"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        verifyNoInteractions(roleAdminService);
    }

    /** 缺失必填字段 400。 */
    @Test
    void missingRequiredFieldReturns400() throws Exception {
        mockMvc.perform(post("/api/admin/roles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"roleCode":"CUSTOM"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        verifyNoInteractions(roleAdminService);
    }

    /** 未知请求体字段被 Jackson 全局严格模式拦截为 400（契约 additionalProperties:false）。 */
    @Test
    void unknownBodyFieldReturns400() throws Exception {
        mockMvc.perform(post("/api/admin/roles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"roleCode":"CUSTOM","roleName":"编辑","status":0}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        verifyNoInteractions(roleAdminService);
    }

    /** 字段类型错误（数字形式的 roleCode/roleName）被严格字符串反序列化拒绝为 400。 */
    @Test
    void nonStringRoleCodeReturns400() throws Exception {
        mockMvc.perform(post("/api/admin/roles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"roleCode":123,"roleName":"编辑"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        mockMvc.perform(post("/api/admin/roles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"roleCode":"CUSTOM","roleName":true}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        verifyNoInteractions(roleAdminService);
    }

    /** 请求体缺失 / 非法 JSON 一律 400。 */
    @Test
    void missingOrMalformedBodyReturns400() throws Exception {
        mockMvc.perform(post("/api/admin/roles")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        mockMvc.perform(post("/api/admin/roles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roleCode\":\"CUSTOM\","))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        verifyNoInteractions(roleAdminService);
    }

    /** 创建接口不接受任何查询参数。 */
    @Test
    void createRejectsUnknownQueryParam() throws Exception {
        mockMvc.perform(post("/api/admin/roles")
                        .param("status", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"roleCode":"CUSTOM","roleName":"编辑"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        verifyNoInteractions(roleAdminService);
    }

    /** 缺少 role:create 权限的登录用户 403，不进入控制器方法。 */
    @Test
    void createWithoutPermissionReturns403() throws Exception {
        when(authorizationQueryService.getForUser(37L))
                .thenReturn(new AuthorizationSnapshot(Set.of("EMPLOYEE"),
                        Set.of("role:list", "role:read")));

        mockMvc.perform(post("/api/admin/roles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"roleCode":"CUSTOM","roleName":"编辑"}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));

        verifyNoInteractions(roleAdminService);
    }

    // ---------- 修改角色（PUT /api/admin/roles/{id}） ----------

    /** 修改成功：200 与完整 Role，服务收到路径 ID 解析出的数值与请求体字段。 */
    @Test
    void updateRoleReturnsFullView() throws Exception {
        when(roleAdminService.update(37L, new RoleUpdateRequest("新名称")))
                .thenReturn(new RoleView("37", "EDITOR", "新名称", 1,
                        java.time.LocalDateTime.of(2026, 11, 2, 16, 0),
                        java.time.LocalDateTime.of(2026, 11, 18, 8, 50)));

        mockMvc.perform(put("/api/admin/roles/37")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"roleName":"新名称"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").value("37"))
                .andExpect(jsonPath("$.data.roleCode").value("EDITOR"))
                .andExpect(jsonPath("$.data.roleName").value("新名称"))
                .andExpect(jsonPath("$.data.status").value(1));

        verify(roleAdminService).update(37L, new RoleUpdateRequest("新名称"));
    }

    /** 修改：角色不存在 404。 */
    @Test
    void updateNonexistentRoleReturns404() throws Exception {
        when(roleAdminService.update(org.mockito.ArgumentMatchers.eq(999L),
                org.mockito.ArgumentMatchers.any()))
                .thenThrow(new BusinessException(ResultCode.NOT_FOUND, "角色不存在"));

        mockMvc.perform(put("/api/admin/roles/999")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"roleName":"新名称"}
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.data").value(nullValue()));
    }

    /** 修改：请求体只允许 roleName——带上 roleCode 视为未知字段，400。 */
    @Test
    void updateRejectsUnknownBodyField() throws Exception {
        mockMvc.perform(put("/api/admin/roles/37")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"roleName":"新名称","roleCode":"HACK"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        verifyNoInteractions(roleAdminService);
    }

    /** 修改：缺失必填字段 400。 */
    @Test
    void updateMissingRoleNameReturns400() throws Exception {
        mockMvc.perform(put("/api/admin/roles/37")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        verifyNoInteractions(roleAdminService);
    }

    /** 修改：字符串形式的数字 roleName（type: string）被严格反序列化拒绝 400。 */
    @Test
    void updateNonStringRoleNameReturns400() throws Exception {
        mockMvc.perform(put("/api/admin/roles/37")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"roleName":123}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        verifyNoInteractions(roleAdminService);
    }

    /** 修改接口不接受任何查询参数。 */
    @Test
    void updateRejectsUnknownQueryParam() throws Exception {
        mockMvc.perform(put("/api/admin/roles/37")
                        .param("status", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"roleName":"新名称"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        verifyNoInteractions(roleAdminService);
    }

    // ---------- 启停角色（PUT /api/admin/roles/{id}/status） ----------

    /** 启停成功：200 且 data=null（NullDataResponse）。 */
    @Test
    void setStatusReturnsNullDataResponse() throws Exception {
        mockMvc.perform(put("/api/admin/roles/37/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":0}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(nullValue()));

        verify(roleAdminService).setStatus(37L, new RoleStatusRequest(0));
    }

    /** 契约：重复设置同一状态幂等返回 200（幂等逻辑在服务层，HTTP 层验证透传）。 */
    @Test
    void settingSameStatusStillReturns200() throws Exception {
        mockMvc.perform(put("/api/admin/roles/37/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":1}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(nullValue()));

        verify(roleAdminService).setStatus(37L, new RoleStatusRequest(1));
    }

    /** 启停：角色不存在 404。 */
    @Test
    void setStatusNonexistentRoleReturns404() throws Exception {
        doThrow(new BusinessException(ResultCode.NOT_FOUND, "角色不存在"))
                .when(roleAdminService).setStatus(org.mockito.ArgumentMatchers.eq(999L),
                        org.mockito.ArgumentMatchers.any());

        mockMvc.perform(put("/api/admin/roles/999/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":1}
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }

    /** 启停：status 非 0/1 由服务层按契约拒绝为 400。 */
    @Test
    void setStatusBeyondEnumReturns400() throws Exception {
        doThrow(new BusinessException(ResultCode.BAD_REQUEST, "状态取值仅支持 0（停用）或 1（启用）"))
                .when(roleAdminService).setStatus(org.mockito.ArgumentMatchers.eq(37L),
                        org.mockito.ArgumentMatchers.any());

        mockMvc.perform(put("/api/admin/roles/37/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":2}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    /** 启停：字符串形式 "1"（type: integer）被严格反序列化拒绝 400。 */
    @Test
    void setStatusNonIntegerBodyReturns400() throws Exception {
        mockMvc.perform(put("/api/admin/roles/37/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"1"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        verifyNoInteractions(roleAdminService);
    }

    /** 启停：缺 status 字段 400。 */
    @Test
    void setStatusMissingFieldReturns400() throws Exception {
        mockMvc.perform(put("/api/admin/roles/37/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        verifyNoInteractions(roleAdminService);
    }
}
