package com.xinghe.cms.content.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xinghe.cms.common.contract.AuthorizationSnapshot;
import com.xinghe.cms.common.contract.CurrentUser;
import com.xinghe.cms.common.exception.BusinessException;
import com.xinghe.cms.common.request.PageQuery;
import com.xinghe.cms.common.response.PageResult;
import com.xinghe.cms.common.response.ResultCode;
import com.xinghe.cms.content.service.CategoryService;
import com.xinghe.cms.content.service.TagService;
import com.xinghe.cms.content.vo.CategoryVO;
import com.xinghe.cms.content.vo.TagVO;
import com.xinghe.cms.permission.service.AuthorizationQueryService;
import com.xinghe.cms.security.CurrentUserProvider;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
class TaxonomyControllerHttpTests {

    private static final long USER_ID = 37L;
    private static final LocalDateTime TIME = LocalDateTime.of(2026, 11, 18, 9, 0);

    @Autowired
    private WebApplicationContext context;

    @MockitoBean
    private CurrentUserProvider currentUserProvider;

    @MockitoBean
    private AuthorizationQueryService authorizationQueryService;

    @MockitoBean
    private CategoryService categoryService;

    @MockitoBean
    private TagService tagService;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/admin/categories", "/api/admin/tags",
            "/api/portal/categories", "/api/portal/tags"})
    void requiresAuthentication(String path) throws Exception {
        when(currentUserProvider.requireCurrentUser()).thenThrow(new BusinessException(ResultCode.UNAUTHORIZED));

        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        verifyNoInteractions(authorizationQueryService, categoryService, tagService);
    }

    @ParameterizedTest
    @CsvSource({
            "/api/admin/categories, portal:category:tree",
            "/api/admin/tags, portal:tag:list",
            "/api/portal/categories, category:tree",
            "/api/portal/tags, tag:list"
    })
    void rejectsPermissionForOtherScope(String path, String permission) throws Exception {
        grant(permission);

        mvc.perform(get(path)).andExpect(status().isForbidden());
        verifyNoInteractions(categoryService, tagService);
    }

    @Test
    void returnsAdminCategoryTreeWithStatusFilter() throws Exception {
        grant("category:tree");
        var category = new CategoryVO("9007199254740993", null, "技术文档", 0, 1, List.of(), TIME, TIME);
        when(categoryService.adminTree(1)).thenReturn(List.of(category));

        mvc.perform(get("/api/admin/categories").param("status", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").isString())
                .andExpect(jsonPath("$.data[0].id").value("9007199254740993"))
                .andExpect(jsonPath("$.data[0].parentId").value(nullValue()))
                .andExpect(jsonPath("$.data[0].children").isEmpty())
                .andExpect(jsonPath("$.data[0].createdAt").value("2026-11-18T09:00:00.000"));
    }

    @Test
    void returnsPortalCategoryOptionsWithPortalPermissionOnly() throws Exception {
        grant("portal:category:tree");
        when(categoryService.portalTree()).thenReturn(List.of());

        mvc.perform(get("/api/portal/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    void forwardsAdminTagFiltersAndReturnsPage() throws Exception {
        grant("tag:list");
        when(tagService.adminPage(any(PageQuery.class), eq("Java"), eq(0))).thenAnswer(invocation -> {
            PageQuery page = invocation.getArgument(0);
            assertThat(page.getCurrent()).isEqualTo(2);
            assertThat(page.getSize()).isEqualTo(5);
            return new PageResult<>(List.of(new TagVO("42", "Java", 0, TIME, TIME)), 6, 2, 5);
        });

        mvc.perform(get("/api/admin/tags").param("current", "2").param("size", "5")
                        .param("keyword", "Java").param("status", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.current").value(2))
                .andExpect(jsonPath("$.data.size").value(5))
                .andExpect(jsonPath("$.data.total").value(6))
                .andExpect(jsonPath("$.data.records[0].id").isString());
    }

    @Test
    void returnsPortalTagPageWithDefaultPagination() throws Exception {
        grant("portal:tag:list");
        when(tagService.portalPage(any(PageQuery.class), eq(null))).thenAnswer(invocation -> {
            PageQuery page = invocation.getArgument(0);
            assertThat(page.getCurrent()).isEqualTo(1);
            assertThat(page.getSize()).isEqualTo(10);
            return new PageResult<>(List.of(), 0, 1, 10);
        });

        mvc.perform(get("/api/portal/tags"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records").isArray())
                .andExpect(jsonPath("$.data.records").isEmpty());
    }

    @ParameterizedTest
    @CsvSource({
            "/api/admin/categories, category:tree, status, 0x1",
            "/api/admin/categories, category:tree, current, 1",
            "/api/admin/tags, tag:list, current, 9223372036854775808",
            "/api/admin/tags, tag:list, size, 1e2",
            "/api/portal/categories, portal:category:tree, status, 1",
            "/api/portal/tags, portal:tag:list, status, 1"
    })
    void rejectsInvalidOrUnknownQueryParameter(String path, String permission, String field, String value)
            throws Exception {
        grant(permission);

        mvc.perform(get(path).param(field, value))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
        verifyNoInteractions(categoryService, tagService);
    }

    @Test
    void rejectsDuplicateQueryParameter() throws Exception {
        grant("tag:list");

        mvc.perform(get("/api/admin/tags").param("status", "0", "1"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(tagService);
    }

    private void grant(String permission) {
        when(currentUserProvider.requireCurrentUser()).thenReturn(new CurrentUser(USER_ID, "employee", null));
        when(authorizationQueryService.getForUser(USER_ID))
                .thenReturn(new AuthorizationSnapshot(Set.of("EMPLOYEE"), Set.of(permission)));
    }
}
