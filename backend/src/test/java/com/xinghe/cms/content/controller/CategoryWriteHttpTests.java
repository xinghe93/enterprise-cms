package com.xinghe.cms.content.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xinghe.cms.common.contract.AuthorizationSnapshot;
import com.xinghe.cms.common.contract.CurrentUser;
import com.xinghe.cms.common.exception.BusinessException;
import com.xinghe.cms.common.response.ResultCode;
import com.xinghe.cms.content.service.CategoryService;
import com.xinghe.cms.content.mapper.PortalCacheMapper;
import com.xinghe.cms.content.vo.CategoryVO;
import com.xinghe.cms.log.service.AuditLogWriter;
import com.xinghe.cms.permission.service.AuthorizationQueryService;
import com.xinghe.cms.security.CurrentUserProvider;
import java.sql.Connection;
import java.time.LocalDateTime;
import java.util.List;
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
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
class CategoryWriteHttpTests {

    private static final String BASE = "/api/admin/categories";
    private static final long USER_ID = 37L;
    private static final LocalDateTime TIME = LocalDateTime.of(2026, 11, 18, 9, 0);
    private static final String CREATE_BODY = """
            {"categoryName":"技术文档"}
            """;

    @Autowired
    private WebApplicationContext context;

    @MockitoBean
    private CurrentUserProvider currentUser;

    @MockitoBean
    private AuthorizationQueryService permissions;

    @MockitoBean
    private CategoryService categories;

    @MockitoBean
    private PortalCacheMapper portalCacheMapper;

    @MockitoBean
    private DataSource dataSource;

    @MockitoBean
    private AuditLogWriter auditWriter;

    private MockMvc mvc;
    private Connection connection;

    @BeforeEach
    void setUp() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        connection = mock(Connection.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true);
        when(connection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_REPEATABLE_READ);
        when(portalCacheMapper.advanceRevisions(any(), any())).thenReturn(1);
    }

    @Test
    void createUsesDefaultsAndWritesAuditBeforeCommit() throws Exception {
        grant("category:create");
        when(categories.create(null, "技术文档", null)).thenReturn(category(0));
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            verify(connection, never()).commit();
            return null;
        }).when(auditWriter).write(any());

        mvc.perform(json(HttpMethod.POST, BASE, CREATE_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value("42"))
                .andExpect(jsonPath("$.data.sortOrder").value(0));

        assertSuccessAudit("category.create", "POST", BASE);
    }

    @Test
    void updateAcceptsExplicitNullParentAndMaximumSortOrder() throws Exception {
        grant("category:update");
        when(categories.update(42L, null, "技术文档", Integer.MAX_VALUE))
                .thenReturn(category(Integer.MAX_VALUE));

        mvc.perform(json(HttpMethod.PUT, BASE + "/42", """
                        {"parentId":null,"categoryName":"技术文档","sortOrder":2147483647}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.parentId").value(nullValue()))
                .andExpect(jsonPath("$.data.sortOrder").value(Integer.MAX_VALUE));

        assertSuccessAudit("category.update", "PUT", BASE + "/42");
    }

    @Test
    void disablesCategoryWithNullResponseData() throws Exception {
        grant("category:status");

        mvc.perform(json(HttpMethod.PUT, BASE + "/42/status", """
                        {"status":0}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(nullValue()));

        verify(categories).setStatus(42L, 0);
        assertSuccessAudit("category.status", "PUT", BASE + "/42/status");
    }

    @Test
    void deletesCategoryWithNullResponseData() throws Exception {
        grant("category:delete");

        mvc.perform(request(HttpMethod.DELETE, BASE + "/42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(nullValue()));

        verify(categories).delete(42L);
        assertSuccessAudit("category.delete", "DELETE", BASE + "/42");
    }

    @Test
    void businessConflictRollsBackAndRecordsFailure() throws Exception {
        grant("category:delete");
        org.mockito.Mockito.doThrow(new BusinessException(ResultCode.CONFLICT)).when(categories).delete(42L);

        mvc.perform(request(HttpMethod.DELETE, BASE + "/42"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409));

        verify(connection).rollback();
        verify(connection, never()).commit();
        verify(auditWriter, never()).write(any());
        verify(auditWriter).writeInNewTransaction(argThat(entry ->
                "category.delete".equals(entry.getOperation()) && "FAILURE".equals(entry.getResult())));
    }

    @Test
    void auditWriteFailureRollsBackBusinessTransaction() throws Exception {
        grant("category:create");
        when(categories.create(null, "技术文档", null)).thenReturn(category(0));
        org.mockito.Mockito.doThrow(new IllegalStateException("audit unavailable")).when(auditWriter).write(any());

        mvc.perform(json(HttpMethod.POST, BASE, CREATE_BODY))
                .andExpect(status().isInternalServerError());

        verify(connection).rollback();
        verify(connection, never()).commit();
    }

    @Test
    void rejectsUnauthenticatedAndUnprivilegedWrites() throws Exception {
        when(currentUser.requireCurrentUser()).thenThrow(new BusinessException(ResultCode.UNAUTHORIZED));
        mvc.perform(json(HttpMethod.POST, BASE, CREATE_BODY)).andExpect(status().isUnauthorized());

        org.mockito.Mockito.doReturn(new CurrentUser(USER_ID, "editor", null)).when(currentUser).requireCurrentUser();
        when(permissions.getForUser(USER_ID))
                .thenReturn(new AuthorizationSnapshot(Set.of("EDITOR"), Set.of("category:tree")));

        mvc.perform(json(HttpMethod.POST, BASE, CREATE_BODY)).andExpect(status().isForbidden());
        mvc.perform(json(HttpMethod.PUT, BASE + "/42", """
                        {"parentId":null,"categoryName":"技术文档","sortOrder":0}
                        """))
                .andExpect(status().isForbidden());
        mvc.perform(json(HttpMethod.PUT, BASE + "/42/status", "{\"status\":1}"))
                .andExpect(status().isForbidden());
        mvc.perform(request(HttpMethod.DELETE, BASE + "/42")).andExpect(status().isForbidden());

        verifyNoInteractions(categories, auditWriter);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"categoryName\":123}",
            "{\"categoryName\":\"技术文档\",\"parentId\":42}",
            "{\"categoryName\":\"技术文档\",\"parentId\":\"9223372036854775808\"}",
            "{\"categoryName\":\"技术文档\",\"sortOrder\":null}",
            "{\"categoryName\":\"技术文档\",\"sortOrder\":1.5}",
            "{\"categoryName\":\"技术文档\",\"status\":1}",
            "[]"
    })
    void rejectsInvalidCreateBody(String body) throws Exception {
        grant("category:create");
        mvc.perform(json(HttpMethod.POST, BASE, body)).andExpect(status().isBadRequest());
        verifyNoInteractions(categories, auditWriter);
    }

    @Test
    void rejectsOmittedParentInvalidStatusAndUnknownDeleteQuery() throws Exception {
        grant("category:update", "category:status", "category:delete");
        mvc.perform(json(HttpMethod.PUT, BASE + "/42", "{\"categoryName\":\"技术文档\",\"sortOrder\":0}"))
                .andExpect(status().isBadRequest());
        mvc.perform(json(HttpMethod.PUT, BASE + "/42/status", "{\"status\":\"1\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(json(HttpMethod.PUT, BASE + "/42/status", "{\"status\":2}"))
                .andExpect(status().isBadRequest());
        mvc.perform(request(HttpMethod.DELETE, BASE + "/042")).andExpect(status().isBadRequest());
        mvc.perform(request(HttpMethod.DELETE, BASE + "/42").param("force", "true"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(categories, auditWriter);
    }

    private void assertSuccessAudit(String operation, String method, String path) throws Exception {
        verify(connection).commit();
        verify(auditWriter).write(argThat(entry ->
                operation.equals(entry.getOperation()) && "SUCCESS".equals(entry.getResult())
                        && method.equals(entry.getHttpMethod()) && path.equals(entry.getRequestUri())
                        && Long.valueOf(USER_ID).equals(entry.getUserId())));
        verify(auditWriter, never()).writeInNewTransaction(any());
    }

    private void grant(String... codes) {
        when(currentUser.requireCurrentUser()).thenReturn(new CurrentUser(USER_ID, "editor", null));
        when(permissions.getForUser(USER_ID)).thenReturn(new AuthorizationSnapshot(Set.of("EDITOR"), Set.of(codes)));
    }

    private static MockHttpServletRequestBuilder json(HttpMethod method, String path, String body) {
        return request(method, path).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static CategoryVO category(int sortOrder) {
        return new CategoryVO("42", null, "技术文档", sortOrder, 1, List.of(), TIME, TIME);
    }
}
