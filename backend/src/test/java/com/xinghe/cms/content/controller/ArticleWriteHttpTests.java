package com.xinghe.cms.content.controller;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xinghe.cms.common.contract.ArticleState;
import com.xinghe.cms.common.contract.AuthorizationSnapshot;
import com.xinghe.cms.common.contract.CurrentUser;
import com.xinghe.cms.common.contract.TagView;
import com.xinghe.cms.common.enums.ArticleStatus;
import com.xinghe.cms.common.exception.BusinessException;
import com.xinghe.cms.common.response.ResultCode;
import com.xinghe.cms.content.dto.ArticleDraft;
import com.xinghe.cms.content.mapper.ArticleMapper;
import com.xinghe.cms.content.mapper.row.ArticleRow;
import com.xinghe.cms.content.service.ArticleDraftService;
import com.xinghe.cms.log.service.AuditLogWriter;
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
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
class ArticleWriteHttpTests {

    private static final String BASE = "/api/admin/articles";
    private static final long ID = 9007199254740993L;
    private static final long USER_ID = 37L;
    private static final LocalDateTime TIME = LocalDateTime.of(2026, 11, 18, 9, 0, 0, 123_000_000);
    private static final ArticleDraft DRAFT = new ArticleDraft("文档", null, " 正文 ", 5L);
    private static final String CREATE = """
            {"title":"文档","content":" 正文 ","categoryId":"5"}
            """;
    private static final String SAVE = """
            {"title":"文档","summary":null,"content":" 正文 ","categoryId":"5","tagIds":["12"],"version":3}
            """;

    @Autowired
    private WebApplicationContext context;
    @MockitoBean
    private ArticleDraftService drafts;
    @MockitoBean
    private ArticleMapper articles;
    @MockitoBean
    private ArticleTagService tags;
    @MockitoBean
    private AuditLogWriter auditWriter;
    @MockitoBean
    private CurrentUserProvider currentUser;
    @MockitoBean
    private AuthorizationQueryService permissions;
    @MockitoBean
    private DataSource dataSource;

    private MockMvc mvc;
    private Connection connection;

    @BeforeEach
    void setUp() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        connection = mock(Connection.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true);
    }

    @Test
    void createsDraftWithDefaultTagsAndReadsBackWithoutSeparateReadPermission() throws Exception {
        grant("article:create");
        when(drafts.create(DRAFT)).thenReturn(ID);
        storedArticle(0, List.of());

        mvc.perform(json(HttpMethod.POST, BASE, CREATE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(Long.toString(ID)))
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.version").value(0))
                .andExpect(jsonPath("$.data.summary").value(nullValue()))
                .andExpect(jsonPath("$.data.publishedAt").value(nullValue()))
                .andExpect(jsonPath("$.data.tags").isEmpty())
                .andExpect(jsonPath("$.data.content").value(" 正文 "));

        var order = inOrder(drafts, tags, articles, auditWriter, connection);
        order.verify(drafts).create(DRAFT);
        order.verify(tags).replaceBindings(ID, List.of());
        order.verify(articles).findDetail(ID, false, null, false);
        order.verify(tags).findByArticleIds(List.of(ID));
        order.verify(auditWriter).write(argThat(entry -> "article.create".equals(entry.getOperation())
                && "SUCCESS".equals(entry.getResult()) && Long.valueOf(USER_ID).equals(entry.getUserId())));
        order.verify(connection).commit();
    }

    @Test
    void savesDraftAndReturnsTagsAndIncrementedVersion() throws Exception {
        grant("article:update");
        storedArticle(4, List.of(new TagView(12L, "Java", 1, TIME, TIME)));

        mvc.perform(json(HttpMethod.PUT, BASE + "/" + ID, SAVE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(4))
                .andExpect(jsonPath("$.data.tags[0].id").value("12"))
                .andExpect(jsonPath("$.data.updatedAt").value("2026-11-18T09:00:00.123"));

        verify(drafts).save(ID, 3, DRAFT);
        verify(tags).replaceBindings(ID, List.of(12L));
        verifySuccessAudit("article.update");
    }

    @Test
    void rollsBackDraftIfTagReplacementFails() throws Exception {
        grant("article:update");
        doThrow(new BusinessException(ResultCode.CONFLICT)).when(tags).replaceBindings(ID, List.of(12L));

        mvc.perform(json(HttpMethod.PUT, BASE + "/" + ID, SAVE)).andExpect(status().isConflict());

        verify(drafts).save(ID, 3, DRAFT);
        verifyNoInteractions(articles);
        verify(connection).rollback();
        verify(connection, never()).commit();
        verify(auditWriter, never()).write(any());
        verify(auditWriter).writeInNewTransaction(argThat(entry -> "article.update".equals(entry.getOperation())
                && "FAILURE".equals(entry.getResult())));
    }

    @Test
    void deletesUsingVersionZeroFromQuery() throws Exception {
        grant("article:delete");
        mvc.perform(request(HttpMethod.DELETE, BASE + "/" + ID).param("version", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(nullValue()));
        verify(drafts).delete(ID, 0);
        verifySuccessAudit("article.delete");
    }

    @Test
    void submitsAndSerializesVersionStateWithStringId() throws Exception {
        grant("article:submit");
        when(drafts.submit(ID, 3)).thenReturn(new ArticleState(ID, ArticleStatus.PENDING_REVIEW, 4, TIME));

        mvc.perform(json(HttpMethod.POST, BASE + "/" + ID + "/submit", "{\"version\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(Long.toString(ID)))
                .andExpect(jsonPath("$.data.status").value("PENDING_REVIEW"))
                .andExpect(jsonPath("$.data.version").value(4))
                .andExpect(jsonPath("$.data.publishedAt").value("2026-11-18T09:00:00.123"));
        verifySuccessAudit("article.submit");
    }

    @Test
    void rejectsWriteWithReadPermissionOnly() throws Exception {
        grant("article:read");
        mvc.perform(json(HttpMethod.POST, BASE, CREATE)).andExpect(status().isForbidden());
        mvc.perform(json(HttpMethod.PUT, BASE + "/" + ID, SAVE)).andExpect(status().isForbidden());
        mvc.perform(request(HttpMethod.DELETE, BASE + "/" + ID).param("version", "0"))
                .andExpect(status().isForbidden());
        mvc.perform(json(HttpMethod.POST, BASE + "/" + ID + "/submit", "{\"version\":0}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(drafts, articles, tags, auditWriter);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"title\":\"文档\",\"content\":\"正文\",\"categoryId\":5}",
            "{\"title\":\"文档\",\"content\":\"正文\",\"categoryId\":\"5\",\"authorId\":\"17\"}",
            "{\"title\":\"文档\",\"content\":\"正文\",\"categoryId\":\"5\",\"tagIds\":null}",
            "{\"title\":\"文档\",\"content\":\"正文\",\"categoryId\":\"5\",\"tagIds\":[\"12\",\"12\"]}",
            "{\"title\":\"文档\",\"content\":\"\u00a0\",\"categoryId\":\"5\"}"
    })
    void rejectsInvalidCreateBody(String body) throws Exception {
        grant("article:create");
        mvc.perform(json(HttpMethod.POST, BASE, body)).andExpect(status().isBadRequest());
        verifyNoInteractions(drafts, articles, tags, auditWriter);
    }

    @Test
    void requiresCompleteSaveAndCanonicalVersions() throws Exception {
        grant("article:update", "article:delete", "article:submit");
        mvc.perform(json(HttpMethod.PUT, BASE + "/" + ID, SAVE.replace("\"summary\":null,", "")))
                .andExpect(status().isBadRequest());
        mvc.perform(json(HttpMethod.PUT, BASE + "/" + ID, SAVE.replace("\"tagIds\":[\"12\"],", "")))
                .andExpect(status().isBadRequest());
        mvc.perform(json(HttpMethod.POST, BASE + "/" + ID + "/submit", "{\"version\":\"3\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(request(HttpMethod.DELETE, BASE + "/" + ID)).andExpect(status().isBadRequest());
        mvc.perform(request(HttpMethod.DELETE, BASE + "/" + ID).param("version", "0", "1"))
                .andExpect(status().isBadRequest());
        mvc.perform(request(HttpMethod.DELETE, BASE + "/" + ID).param("version", "2147483648"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(drafts, articles, tags, auditWriter);
    }

    private void storedArticle(int version, List<TagView> articleTags) {
        when(articles.findDetail(ID, false, null, false)).thenReturn(new ArticleRow(ID,
                DRAFT.title(), null, 5L, "分类", USER_ID, "作者", ArticleStatus.DRAFT,
                "ALL", version, null, TIME, TIME, DRAFT.content()));
        when(tags.findByArticleIds(List.of(ID))).thenReturn(Map.of(ID, articleTags));
    }

    private void verifySuccessAudit(String operation) throws Exception {
        verify(auditWriter).write(argThat(entry -> operation.equals(entry.getOperation())
                && "SUCCESS".equals(entry.getResult())));
        verify(connection).commit();
    }

    private void grant(String... codes) {
        when(currentUser.requireCurrentUser()).thenReturn(new CurrentUser(USER_ID, "editor", null));
        when(permissions.getForUser(USER_ID))
                .thenReturn(new AuthorizationSnapshot(Set.of("EDITOR"), Set.of(codes)));
    }

    private static MockHttpServletRequestBuilder json(HttpMethod method, String path, String body) {
        return request(method, path).contentType(MediaType.APPLICATION_JSON).content(body);
    }
}
