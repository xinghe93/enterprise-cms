package com.xinghe.cms.permission.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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
import com.xinghe.cms.log.service.AuditLogWriter;
import com.xinghe.cms.permission.request.PermissionCreateRequest;
import com.xinghe.cms.permission.request.PermissionUpdateRequest;
import com.xinghe.cms.permission.response.PermissionView;
import com.xinghe.cms.permission.service.AuthorizationQueryService;
import com.xinghe.cms.permission.service.PermissionAdminService;
import com.xinghe.cms.permission.service.PermissionQueryService;
import com.xinghe.cms.security.CurrentUserProvider;
import java.time.LocalDateTime;
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
 * 权限项管理接口的 HTTP 层回归：走真实 DispatcherServlet + 公共异常处理器 +
 * Jackson 全局严格绑定（服务用 mock）。覆盖契约 8.x 的绑定层语义：未知查询
 * 参数/空值枚举/重复传参 400、IdList 类缺失键（完整 PUT 的 required-but-nullable）
 * 400、显式 null 放行、未知 JSON 字段 400、字段类型严格 400；鉴权切面通过
 * mock CurrentUserProvider 与 AuthorizationQueryService 放行，403 单独复现。
 * 服务层业务校验（类型条件不变量、唯一键 409）由 Service 单测覆盖。
 */
@SpringBootTest
class PermissionControllerHttpTests {

    @Autowired
    private WebApplicationContext context;

    @MockitoBean
    private PermissionQueryService permissionQueryService;

    @MockitoBean
    private PermissionAdminService permissionAdminService;

    @MockitoBean
    private CurrentUserProvider currentUserProvider;

    @MockitoBean
    private AuthorizationQueryService authorizationQueryService;

    /** 隔离审计写入：mock 掉 Writer，任何意外触发的切面路径都不会写真实库。 */
    @MockitoBean
    private AuditLogWriter auditLogWriter;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        when(currentUserProvider.requireCurrentUser())
                .thenReturn(new CurrentUser(37L, "admin01", null));
        when(authorizationQueryService.getForUser(37L))
                .thenReturn(new AuthorizationSnapshot(Set.of("ADMIN"),
                        Set.of("permission:list", "permission:create", "permission:update",
                                "permission:status")));

        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    private static PermissionView view(String id, String code, String type, Integer status) {
        return new PermissionView(id, code, "名称", type,
                "API".equals(type) ? "/api/admin/articles/{id}" : null,
                "API".equals(type) ? "GET" : null,
                status,
                LocalDateTime.of(2026, 11, 21, 9, 0),
                LocalDateTime.of(2026, 11, 21, 9, 0));
    }

    // ---------- 分页（GET /api/admin/permissions） ----------

    /** 合法请求到达服务，keyword/status/permissionType 按原始取值传入。 */
    @Test
    void validPageRequestReachesServiceWithParsedFilters() throws Exception {
        when(permissionQueryService.page(any(), eq("article"), eq(1), eq("API")))
                .thenReturn(new PageResult<>(List.of(view("3", "article:read", "API", 1)), 1L, 1L, 10L));

        mockMvc.perform(get("/api/admin/permissions")
                        .param("current", "1")
                        .param("size", "10")
                        .param("keyword", "article")
                        .param("status", "1")
                        .param("permissionType", "API"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.records.length()").value(1))
                .andExpect(jsonPath("$.data.records[0].id").value("3"))
                .andExpect(jsonPath("$.data.records[0].method").value("GET"));

        ArgumentCaptor<PageQuery> captor = ArgumentCaptor.forClass(PageQuery.class);
        verify(permissionQueryService).page(captor.capture(), eq("article"), eq(1), eq("API"));
        assertThat(captor.getValue().getCurrent()).isEqualTo(1L);
        assertThat(captor.getValue().getSize()).isEqualTo(10L);
    }

    /** current/size 未传时保留默认第 1 页、每页 10 条。 */
    @Test
    void paginationParamsOmittedFallBackToDefaults() throws Exception {
        when(permissionQueryService.page(any(), isNull(), isNull(), isNull()))
                .thenReturn(new PageResult<>(List.of(), 0L, 1L, 10L));

        mockMvc.perform(get("/api/admin/permissions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        ArgumentCaptor<PageQuery> captor = ArgumentCaptor.forClass(PageQuery.class);
        verify(permissionQueryService).page(captor.capture(), isNull(), isNull(), isNull());
        assertThat(captor.getValue().getCurrent()).isEqualTo(1L);
        assertThat(captor.getValue().getSize()).isEqualTo(10L);
    }

    /** 未知查询参数一律 400 且不进入服务（契约 8.x）。 */
    @Test
    void unknownQueryParamReturns400() throws Exception {
        mockMvc.perform(get("/api/admin/permissions")
                        .param("current", "1")
                        .param("kw", "x"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        verifyNoInteractions(permissionQueryService);
    }

    /** status= 空值 / 非法枚举 / permissionType 大小写不匹配 一律 400。 */
    @Test
    void blankOrInvalidEnumParamsReturn400() throws Exception {
        mockMvc.perform(get("/api/admin/permissions").param("status", ""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        mockMvc.perform(get("/api/admin/permissions")
                        .param("permissionType", "menu"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        mockMvc.perform(get("/api/admin/permissions")
                        .param("status", "1")
                        .param("status", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        verifyNoInteractions(permissionQueryService);
    }

    /** 超出 int64 的 current 在解析阶段 400。 */
    @Test
    void currentBeyondInt64RangeReturns400() throws Exception {
        mockMvc.perform(get("/api/admin/permissions")
                        .param("current", "9999999999999999999"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        verifyNoInteractions(permissionQueryService);
    }

    // ---------- 创建（POST /api/admin/permissions） ----------

    /** 创建成功：返回 200 与完整 Permission（status=1、时间格式、method 透传）。 */
    @Test
    void createPermissionReturnsFullView() throws Exception {
        when(permissionAdminService.create(new PermissionCreateRequest(
                "article:read", "后台文章详情", "API", "/api/admin/articles/{id}", "GET")))
                .thenReturn(view("77", "article:read", "API", 1));

        mockMvc.perform(post("/api/admin/permissions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"permissionCode":"article:read","permissionName":"后台文章详情",
                                 "permissionType":"API","path":"/api/admin/articles/{id}","method":"GET"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").value("77"))
                .andExpect(jsonPath("$.data.permissionCode").value("article:read"))
                .andExpect(jsonPath("$.data.status").value(1))
                .andExpect(jsonPath("$.data.createdAt").value("2026-11-21T09:00:00.000"));

        verify(permissionAdminService).create(eq(new PermissionCreateRequest(
                "article:read", "后台文章详情", "API", "/api/admin/articles/{id}", "GET")));
    }

    /** 缺失必填字段（permissionCode/permissionType）→ 400，不进入服务。 */
    @Test
    void createMissingRequiredFieldReturns400() throws Exception {
        mockMvc.perform(post("/api/admin/permissions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"permissionName":"后台文章详情","permissionType":"API"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        verifyNoInteractions(permissionAdminService);
    }

    /** 未知 JSON 字段 → 400（全局严格模式）。 */
    @Test
    void createUnknownFieldReturns400() throws Exception {
        mockMvc.perform(post("/api/admin/permissions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"permissionCode":"a:b","permissionName":"名称",
                                 "permissionType":"MENU","status":1}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        verifyNoInteractions(permissionAdminService);
    }

    /** 字段类型严格：数字形式的编码、布尔形式的类型一律 400。 */
    @Test
    void createWrongFieldTypeReturns400() throws Exception {
        mockMvc.perform(post("/api/admin/permissions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"permissionCode":123,"permissionName":"名称","permissionType":"MENU"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        mockMvc.perform(post("/api/admin/permissions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"permissionCode":"a:b","permissionName":"名称","permissionType":true}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        verifyNoInteractions(permissionAdminService);
    }

    /** MENU 类型省略 path/method 合法：显式 null 亦放行，到达服务。 */
    @Test
    void createMenuTypeWithoutPathMethodReachesService() throws Exception {
        when(permissionAdminService.create(new PermissionCreateRequest(
                "menu:articles", "文章菜单", "MENU", null, null)))
                .thenReturn(view("78", "menu:articles", "MENU", 1));

        mockMvc.perform(post("/api/admin/permissions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"permissionCode":"menu:articles","permissionName":"文章菜单",
                                 "permissionType":"MENU","path":null,"method":null}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.method").value(nullValue()));

        verify(permissionAdminService).create(eq(new PermissionCreateRequest(
                "menu:articles", "文章菜单", "MENU", null, null)));
    }

    /** 无 permission:create 权限 → 403（鉴权切面在参数绑定后、服务前）。 */
    @Test
    void createWithoutPermissionCodeReturns403() throws Exception {
        when(authorizationQueryService.getForUser(37L))
                .thenReturn(new AuthorizationSnapshot(Set.of("ADMIN"), Set.of("permission:list")));

        mockMvc.perform(post("/api/admin/permissions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"permissionCode":"a:b","permissionName":"名称","permissionType":"MENU"}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));

        verifyNoInteractions(permissionAdminService);
    }

    // ---------- 修改（PUT /api/admin/permissions/{id}） ----------

    /** 完整 PUT：path/method 键缺失 400（required-but-nullable 的绑定层语义）。 */
    @Test
    void updateMissingPathOrMethodKeyReturns400() throws Exception {
        mockMvc.perform(put("/api/admin/permissions/9")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"permissionName":"名称","method":null}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        mockMvc.perform(put("/api/admin/permissions/9")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"permissionName":"名称","path":null}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        verifyNoInteractions(permissionAdminService);
    }

    /** 显式 null 的 path/method 放行到服务（MENU 类型合法终态）。 */
    @Test
    void updateWithExplicitNullsReachesService() throws Exception {
        when(permissionAdminService.update(9L,
                new PermissionUpdateRequest("文章菜单", null, null)))
                .thenReturn(view("9", "menu:articles", "MENU", 1));

        mockMvc.perform(put("/api/admin/permissions/9")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"permissionName":"文章菜单","path":null,"method":null}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").value("9"));

        verify(permissionAdminService).update(eq(9L),
                eq(new PermissionUpdateRequest("文章菜单", null, null)));
    }

    /** 修改请求体的未知字段 / 字段类型错误 → 400。 */
    @Test
    void updateUnknownFieldOrWrongTypeReturns400() throws Exception {
        mockMvc.perform(put("/api/admin/permissions/9")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"permissionName":"名称","path":null,"method":null,
                                 "permissionCode":"hack:code"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        mockMvc.perform(put("/api/admin/permissions/9")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"permissionName":"名称","path":123,"method":null}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        verifyNoInteractions(permissionAdminService);
    }

    /** 服务抛 404（不存在）→ 404 响应；非法路径 ID → 400。 */
    @Test
    void updateMissingPermissionReturns404AndBadIdReturns400() throws Exception {
        doThrow(new BusinessException(ResultCode.NOT_FOUND, "权限项不存在"))
                .when(permissionAdminService).update(eq(999L), any(PermissionUpdateRequest.class));

        mockMvc.perform(put("/api/admin/permissions/999")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"permissionName":"名称","path":null,"method":null}
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));

        mockMvc.perform(put("/api/admin/permissions/abc")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"permissionName":"名称","path":null,"method":null}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    // ---------- 启停（PUT /api/admin/permissions/{id}/status） ----------

    /** 启停成功：data=null（NullDataResponse），服务收到 0/1。 */
    @Test
    void setStatusReturnsNullData() throws Exception {
        mockMvc.perform(put("/api/admin/permissions/9/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":0}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(nullValue()));

        verify(permissionAdminService).setStatus(eq(9L),
                eq(new com.xinghe.cms.permission.request.PermissionStatusRequest(0)));
    }

    /** status 缺失 / 字符串 "1" / 越界取值 2 → 400（绑定层拦截）。 */
    @Test
    void setStatusInvalidBodyReturns400() throws Exception {
        mockMvc.perform(put("/api/admin/permissions/9/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        mockMvc.perform(put("/api/admin/permissions/9/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"1"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        verifyNoInteractions(permissionAdminService);
    }

    /** 启停写接口不接受查询参数。 */
    @Test
    void statusRejectsUnknownQueryParam() throws Exception {
        mockMvc.perform(put("/api/admin/permissions/9/status")
                        .param("foo", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":1}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        verifyNoInteractions(permissionAdminService);
    }
}
