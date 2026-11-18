package com.xinghe.cms.content.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.xinghe.cms.common.contract.AuthorizationSnapshot;
import com.xinghe.cms.common.contract.CurrentUser;
import com.xinghe.cms.common.contract.TagView;
import com.xinghe.cms.common.enums.ArticleStatus;
import com.xinghe.cms.common.exception.BusinessException;
import com.xinghe.cms.common.response.ResultCode;
import com.xinghe.cms.content.mapper.ArticleMapper;
import com.xinghe.cms.content.mapper.row.ArticleRow;
import com.xinghe.cms.content.service.CategoryService;
import com.xinghe.cms.permission.service.AuthorizationQueryService;
import com.xinghe.cms.review.service.ArticleTagService;
import com.xinghe.cms.security.CurrentUserProvider;
import java.sql.Connection;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
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
class ArticleReadHttpTests {

    private static final String ADMIN = "/api/admin/articles";
    private static final String PORTAL = "/api/portal/articles";
    private static final long USER_ID = 37L;
    private static final long ARTICLE_ID = 9007199254740993L;
    private static final LocalDateTime TIME = LocalDateTime.of(2026, 11, 18, 9, 0, 0, 123_000_000);

    @Autowired
    private WebApplicationContext context;
    @MockitoBean
    private ArticleMapper articles;
    @MockitoBean
    private CategoryService categories;
    @MockitoBean
    private ArticleTagService tags;
    @MockitoBean
    private CurrentUserProvider currentUser;
    @MockitoBean
    private AuthorizationQueryService permissions;
    @MockitoBean
    private DataSource dataSource;

    private MockMvc mvc;

    @BeforeEach
    void setUp() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        when(dataSource.getConnection()).thenReturn(mock(Connection.class));
    }

    @Test
    void adminPageKeepsAuthorScopeAndLoadsTagsInOneBatch() throws Exception {
        grant("EDITOR", "article:list");
        when(categories.descendantIds(5L)).thenReturn(Set.of(5L, 6L));
        when(articles.findPage(any(), eq(false), eq(USER_ID), eq(USER_ID), eq(false), eq(ArticleStatus.DRAFT),
                eq(Set.of(5L, 6L)), eq(12L), eq("%!%!_!!%"))).thenAnswer(invocation -> {
            Page<ArticleRow> page = invocation.getArgument(0);
            assertThat(page.getCurrent()).isEqualTo(2);
            assertThat(page.getSize()).isEqualTo(5);
            page.setTotal(7);
            return page.setRecords(List.of(row(ARTICLE_ID, ArticleStatus.DRAFT, null),
                    row(42L, ArticleStatus.DRAFT, null)));
        });
        when(tags.findByArticleIds(List.of(ARTICLE_ID, 42L))).thenReturn(Map.of(
                ARTICLE_ID, List.of(new TagView(12L, "Java", 1, TIME, TIME)), 42L, List.of()));

        mvc.perform(get(ADMIN).param("current", "2").param("size", "5").param("authorId", Long.toString(USER_ID))
                        .param("categoryId", "5").param("tagId", "12").param("status", "DRAFT")
                        .param("keyword", " %_! "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(7))
                .andExpect(jsonPath("$.data.current").value(2))
                .andExpect(jsonPath("$.data.size").value(5))
                .andExpect(jsonPath("$.data.records[0].id").value(Long.toString(ARTICLE_ID)))
                .andExpect(jsonPath("$.data.records[0].authorId").value("37"))
                .andExpect(jsonPath("$.data.records[0].categoryId").value("5"))
                .andExpect(jsonPath("$.data.records[0].summary").value(nullValue()))
                .andExpect(jsonPath("$.data.records[0].publishedAt").value(nullValue()))
                .andExpect(jsonPath("$.data.records[0].updatedAt").value("2026-11-18T09:00:00.123"))
                .andExpect(jsonPath("$.data.records[0].tags[0].id").value("12"))
                .andExpect(jsonPath("$.data.records[1].tags").isEmpty())
                .andExpect(jsonPath("$.data.records[0].content").doesNotHaveJsonPath());
        verify(tags).findByArticleIds(List.of(ARTICLE_ID, 42L));
    }

    @Test
    void portalPageIsRestrictedToPublishedReadPathAndSkipsLookupForEmptyPage() throws Exception {
        grant("EMPLOYEE", "portal:article:list");
        when(articles.findPage(any(), eq(true), isNull(), isNull(), eq(false), isNull(),
                isNull(), eq(12L), isNull())).thenAnswer(invocation -> invocation.getArgument(0));

        mvc.perform(get(PORTAL).param("tagId", "12"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.current").value(1))
                .andExpect(jsonPath("$.data.size").value(10))
                .andExpect(jsonPath("$.data.records").isArray())
                .andExpect(jsonPath("$.data.records").isEmpty());
        verifyNoInteractions(tags);
    }

    @Test
    void portalDetailIsFlatAndIncludesContentAndTags() throws Exception {
        grant("EMPLOYEE", "portal:article:read");
        when(articles.findDetail(ARTICLE_ID, true, null, false))
                .thenReturn(row(ARTICLE_ID, ArticleStatus.PUBLISHED, "正文"));
        when(tags.findByArticleIds(List.of(ARTICLE_ID))).thenReturn(Map.of(ARTICLE_ID,
                List.of(new TagView(12L, "Java", 0, TIME, TIME))));

        mvc.perform(get(PORTAL + "/" + ARTICLE_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(Long.toString(ARTICLE_ID)))
                .andExpect(jsonPath("$.data.content").value("正文"))
                .andExpect(jsonPath("$.data.tags[0].status").value(0))
                .andExpect(jsonPath("$.data.publishedAt").value("2026-11-18T09:00:00.123"))
                .andExpect(jsonPath("$.data.article").doesNotHaveJsonPath());
    }

    @Test
    void deniedAdminDetailAndHiddenPortalDetailDoNotLoadTags() throws Exception {
        grant("EDITOR", "article:read", "portal:article:read");
        when(articles.findDetail(42L, false, USER_ID, false)).thenReturn(row(42L, ArticleStatus.DRAFT, null));

        mvc.perform(get(ADMIN + "/42")).andExpect(status().isForbidden());
        mvc.perform(get(PORTAL + "/42")).andExpect(status().isNotFound());
        verify(articles).findDetail(42L, true, null, false);
        verifyNoInteractions(tags);
    }

    @ParameterizedTest
    @CsvSource({"/api/admin/articles, portal:article:list", "/api/admin/articles/42, article:list",
            "/api/portal/articles, article:list", "/api/portal/articles/42, portal:article:list"})
    void requiresSpecificPermissionForEveryRoute(String path, String allowedPermission) throws Exception {
        grant("EMPLOYEE", allowedPermission);
        mvc.perform(get(path)).andExpect(status().isForbidden());
        verifyNoInteractions(articles, tags);
    }

    @Test
    void requiresAuthentication() throws Exception {
        when(currentUser.requireCurrentUser()).thenThrow(new BusinessException(ResultCode.UNAUTHORIZED));
        mvc.perform(get(PORTAL)).andExpect(status().isUnauthorized());
        verifyNoInteractions(permissions, articles, tags);
    }

    @ParameterizedTest
    @CsvSource({"/api/admin/articles, article:list, status, draft",
            "/api/admin/articles, article:list, current, 9223372036854775808",
            "/api/admin/articles, article:list, authorId, 01",
            "/api/admin/articles, article:list, size, 101",
            "/api/portal/articles, portal:article:list, status, PUBLISHED",
            "/api/portal/articles, portal:article:list, authorId, 37",
            "/api/portal/articles/42, portal:article:read, tagId, 12"})
    void rejectsUnsupportedAndInvalidParameters(String path, String permission, String field, String value)
            throws Exception {
        grant("EDITOR", permission);
        mvc.perform(get(path).param(field, value)).andExpect(status().isBadRequest());
        verifyNoInteractions(articles, tags);
    }

    @ParameterizedTest
    @ValueSource(strings = {"042", "9223372036854775808"})
    void rejectsInvalidDetailIds(String id) throws Exception {
        grant("EMPLOYEE", "portal:article:read");
        mvc.perform(get(PORTAL + "/" + id)).andExpect(status().isBadRequest());
        verifyNoInteractions(articles, tags);
    }

    @Test
    void rejectsRepeatedParameters() throws Exception {
        grant("EDITOR", "article:list");
        mvc.perform(get(ADMIN).param("status", "DRAFT", "PUBLISHED")).andExpect(status().isBadRequest());
        verifyNoInteractions(articles, tags);
    }

    private void grant(String role, String... codes) {
        when(currentUser.requireCurrentUser()).thenReturn(new CurrentUser(USER_ID, "employee", null));
        when(permissions.getForUser(USER_ID))
                .thenReturn(new AuthorizationSnapshot(Set.of(role), Set.of(codes)));
    }

    private static ArticleRow row(long id, ArticleStatus status, String content) {
        return new ArticleRow(id, "知识文档", null, 5L, "技术", USER_ID, "编辑",
                status, "ALL", 0, status == ArticleStatus.PUBLISHED ? TIME : null, TIME, TIME, content);
    }
}
