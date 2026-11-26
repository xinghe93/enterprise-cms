package com.xinghe.cms.content.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
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
import com.xinghe.cms.content.entity.Tag;
import com.xinghe.cms.content.mapper.TagMapper;
import com.xinghe.cms.content.mapper.PortalCacheMapper;
import com.xinghe.cms.log.service.AuditLogWriter;
import com.xinghe.cms.permission.service.AuthorizationQueryService;
import com.xinghe.cms.security.CurrentUserProvider;
import java.sql.Connection;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
class TagWriteHttpTests {

    private static final String BASE = "/api/admin/tags";
    private static final String NAME_BODY = """
            {"tagName":" Java "}
            """;

    @Autowired
    private WebApplicationContext context;

    @MockitoBean
    private CurrentUserProvider currentUser;

    @MockitoBean
    private AuthorizationQueryService permissions;

    @MockitoBean
    private TagMapper tagMapper;

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
        when(portalCacheMapper.advanceRevisions(any(), any())).thenReturn(1);
    }

    @Test
    void createsEnabledTagAndWritesAuditBeforeCommit() throws Exception {
        grant("tag:create");
        mockInsert();
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            verify(connection, never()).commit();
            return null;
        }).when(auditWriter).write(any());

        mvc.perform(json(HttpMethod.POST, BASE, NAME_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value("42"))
                .andExpect(jsonPath("$.data.tagName").value("Java"))
                .andExpect(jsonPath("$.data.status").value(1));
        verifySuccess("tag.create", "POST", BASE);
    }

    @Test
    void updatesNameWithoutChangingExistingStatus() throws Exception {
        grant("tag:update");
        when(tagMapper.selectForUpdate(42L)).thenReturn(tag("Old", 0));
        when(tagMapper.selectById(42L)).thenReturn(tag("Java", 0));

        mvc.perform(json(HttpMethod.PUT, BASE + "/42", NAME_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tagName").value("Java"))
                .andExpect(jsonPath("$.data.status").value(0));
        verifySuccess("tag.update", "PUT", BASE + "/42");
    }

    @Test
    void setsTagStatus() throws Exception {
        grant("tag:status");
        when(tagMapper.selectForUpdate(42L)).thenReturn(tag("Java", 1));

        mvc.perform(json(HttpMethod.PUT, BASE + "/42/status", "{\"status\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(nullValue()));
        verify(tagMapper).update(any());
        verifySuccess("tag.status", "PUT", BASE + "/42/status");
    }

    @Test
    void deletesUnboundTag() throws Exception {
        grant("tag:delete");
        when(tagMapper.selectForUpdate(42L)).thenReturn(tag("Java", 1));

        mvc.perform(request(HttpMethod.DELETE, BASE + "/42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(nullValue()));
        verify(tagMapper).deleteById(42L);
        verifySuccess("tag.delete", "DELETE", BASE + "/42");
    }

    @Test
    void boundTagReturnsConflictAndRecordsFailedAudit() throws Exception {
        grant("tag:delete");
        when(tagMapper.selectForUpdate(42L)).thenReturn(tag("Java", 1));
        when(tagMapper.hasArticleReference(42L)).thenReturn(true);

        mvc.perform(request(HttpMethod.DELETE, BASE + "/42")).andExpect(status().isConflict());
        verify(tagMapper, never()).deleteById(42L);
        verify(connection).rollback();
        verify(connection, never()).commit();
        verify(auditWriter, never()).write(any());
        verify(auditWriter).writeInNewTransaction(argThat(entry ->
                "tag.delete".equals(entry.getOperation()) && "FAILURE".equals(entry.getResult())));
    }

    @Test
    void duplicateNameReturnsConflict() throws Exception {
        grant("tag:create");
        when(tagMapper.insert(any(Tag.class))).thenThrow(new DuplicateKeyException("duplicate"));

        mvc.perform(json(HttpMethod.POST, BASE, NAME_BODY)).andExpect(status().isConflict());
        verify(connection).rollback();
    }

    @Test
    void auditWriteFailurePreventsTagWriteFromCommitting() throws Exception {
        grant("tag:create");
        mockInsert();
        doThrow(new IllegalStateException("audit unavailable")).when(auditWriter).write(any());

        mvc.perform(json(HttpMethod.POST, BASE, NAME_BODY)).andExpect(status().isInternalServerError());
        verify(connection).rollback();
        verify(connection, never()).commit();
    }

    @Test
    void requiresAuthenticationAndRejectsReadOnlyPermissionForEveryWrite() throws Exception {
        when(currentUser.requireCurrentUser()).thenThrow(new BusinessException(ResultCode.UNAUTHORIZED));
        mvc.perform(json(HttpMethod.POST, BASE, NAME_BODY)).andExpect(status().isUnauthorized());

        grant("tag:list");
        mvc.perform(json(HttpMethod.POST, BASE, NAME_BODY)).andExpect(status().isForbidden());
        mvc.perform(json(HttpMethod.PUT, BASE + "/42", NAME_BODY)).andExpect(status().isForbidden());
        mvc.perform(json(HttpMethod.PUT, BASE + "/42/status", "{\"status\":0}")).andExpect(status().isForbidden());
        mvc.perform(request(HttpMethod.DELETE, BASE + "/42")).andExpect(status().isForbidden());
        verifyNoInteractions(tagMapper, auditWriter);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"tagName\":null}", "{\"tagName\":42}", "{\"tagName\":\" \"}",
            "{\"tagName\":\"\u00a0\"}", "{\"tagName\":\"Java\",\"status\":1}"})
    void rejectsInvalidTagBody(String body) throws Exception {
        grant("tag:create");
        mvc.perform(json(HttpMethod.POST, BASE, body)).andExpect(status().isBadRequest());
        verifyNoInteractions(tagMapper);
    }

    @Test
    void rejectsOversizedNameInvalidStatusPathAndQuery() throws Exception {
        grant("tag:create", "tag:status", "tag:delete");
        mvc.perform(json(HttpMethod.POST, BASE, "{\"tagName\":\"" + "文".repeat(65) + "\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(json(HttpMethod.PUT, BASE + "/42/status", "{\"status\":\"1\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(json(HttpMethod.PUT, BASE + "/42/status", "{\"status\":2}"))
                .andExpect(status().isBadRequest());
        mvc.perform(request(HttpMethod.DELETE, BASE + "/042")).andExpect(status().isBadRequest());
        mvc.perform(request(HttpMethod.DELETE, BASE + "/42").param("force", "true"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(tagMapper);
    }

    private void grant(String... codes) {
        org.mockito.Mockito.doReturn(new CurrentUser(37L, "editor", null)).when(currentUser).requireCurrentUser();
        when(permissions.getForUser(37L)).thenReturn(new AuthorizationSnapshot(Set.of("EDITOR"), Set.of(codes)));
    }

    private void mockInsert() {
        when(tagMapper.insert(any(Tag.class))).thenAnswer(invocation -> {
            Tag inserted = invocation.getArgument(0);
            inserted.setId(42L);
            assertThat(inserted.getTagName()).isEqualTo("Java");
            assertThat(inserted.getStatus()).isEqualTo(1);
            return 1;
        });
        when(tagMapper.selectById(42L)).thenReturn(tag("Java", 1));
    }

    private void verifySuccess(String operation, String method, String path) throws Exception {
        verify(connection).commit();
        verify(auditWriter).write(argThat(entry ->
                operation.equals(entry.getOperation()) && "SUCCESS".equals(entry.getResult())
                        && method.equals(entry.getHttpMethod()) && path.equals(entry.getRequestUri())
                        && Long.valueOf(37L).equals(entry.getUserId())));
        verify(auditWriter, never()).writeInNewTransaction(any());
    }

    private static MockHttpServletRequestBuilder json(HttpMethod method, String path, String body) {
        return request(method, path).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static Tag tag(String name, int status) {
        Tag tag = new Tag();
        tag.setId(42L);
        tag.setTagName(name);
        tag.setStatus(status);
        return tag;
    }
}
