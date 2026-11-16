package com.xinghe.cms.content.service;

import com.xinghe.cms.common.contract.ArticleSnapshot;
import com.xinghe.cms.common.contract.ArticleState;
import com.xinghe.cms.common.contract.AuthorizationSnapshot;
import com.xinghe.cms.common.contract.CurrentUser;
import com.xinghe.cms.common.enums.ArticleAction;
import com.xinghe.cms.common.enums.ArticleStatus;
import com.xinghe.cms.common.exception.BusinessException;
import com.xinghe.cms.common.response.ResultCode;
import com.xinghe.cms.content.mapper.ArticleMapper;
import com.xinghe.cms.permission.service.AuthorizationQueryService;
import com.xinghe.cms.security.CurrentUserProvider;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class ArticleLifecycleTests {

    private static final AuthorizationSnapshot AUTHOR = new AuthorizationSnapshot(
            Set.of("EDITOR"), Set.of("article:submit", "article:update", "review:list"));

    private final ArticleMapper mapper = mock(ArticleMapper.class);
    private final CurrentUserProvider users = mock(CurrentUserProvider.class);
    private final AuthorizationQueryService permissions = mock(AuthorizationQueryService.class);
    private final ArticleLifecycle lifecycle = new ArticleLifecycle(mapper, users, permissions);
    private final ArticleAccessService access = lifecycle;
    private final ArticleWorkflowService workflow = lifecycle;

    @Test
    void historyCanReadOwnDraftWithoutArticleReadPermission() {
        authenticate(2L, AUTHOR);
        ArticleSnapshot article = draft(4);
        when(mapper.findSnapshot(1L)).thenReturn(article);

        assertThat(access.requireReadable(1L)).isEqualTo(article);
    }

    @Test
    void historyCannotReadAnotherUsersDraft() {
        authenticate(7L, AUTHOR);
        when(mapper.findSnapshot(1L)).thenReturn(draft(4));

        assertThatThrownBy(() -> access.requireReadable(1L))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getResultCode()).isEqualTo(ResultCode.FORBIDDEN));
    }

    @Test
    void missingOperationPermissionStopsBeforeLockingArticle() {
        authenticate(2L, AUTHOR);

        assertThatThrownBy(() -> workflow.transition(1L, 4, ArticleAction.PUBLISH))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getResultCode()).isEqualTo(ResultCode.FORBIDDEN));
        verifyNoInteractions(mapper);
    }

    @Test
    void staleVersionDoesNotIssueAnUpdate() {
        authenticate(2L, AUTHOR);
        when(mapper.lockSnapshot(1L)).thenReturn(draft(8));

        assertThatThrownBy(() -> workflow.transition(1L, 7, ArticleAction.SUBMIT))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getResultCode()).isEqualTo(ResultCode.CONFLICT));
        verify(mapper).lockSnapshot(1L);
        verifyNoMoreInteractions(mapper);
    }

    @Test
    void maximumVersionCannotBeIncremented() {
        authenticate(2L, AUTHOR);
        when(mapper.lockSnapshot(1L)).thenReturn(draft(Integer.MAX_VALUE));

        assertThatThrownBy(() -> access.lockEditable(1L, Integer.MAX_VALUE, ArticleAction.SAVE))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getResultCode()).isEqualTo(ResultCode.CONFLICT));
        verify(mapper).lockSnapshot(1L);
        verifyNoMoreInteractions(mapper);
    }

    @Test
    void batchPublishUsesBatchPermissionAndReturnsDatabaseState() {
        authenticate(7L, new AuthorizationSnapshot(Set.of("CUSTOM"), Set.of("article:publish-batch")));
        ArticleSnapshot approved = new ArticleSnapshot(1L, 2L, 3L, ArticleStatus.APPROVED, 4, null);
        LocalDateTime publishedAt = LocalDateTime.of(2026, 11, 12, 8, 0);
        ArticleSnapshot published = new ArticleSnapshot(1L, 2L, 3L, ArticleStatus.PUBLISHED, 5, publishedAt);
        when(mapper.lockSnapshot(1L)).thenReturn(approved);
        when(mapper.changeState(approved, ArticleStatus.PUBLISHED)).thenReturn(1);
        when(mapper.findSnapshot(1L)).thenReturn(published);

        assertThat(workflow.transition(1L, 4, ArticleAction.BATCH_PUBLISH))
                .isEqualTo(new ArticleState(1L, ArticleStatus.PUBLISHED, 5, publishedAt));
        verify(permissions).getForUser(7L);
        verify(mapper).changeState(approved, ArticleStatus.PUBLISHED);
    }

    private void authenticate(long userId, AuthorizationSnapshot snapshot) {
        when(users.requireCurrentUser()).thenReturn(new CurrentUser(userId, "editor", null));
        when(permissions.getForUser(userId)).thenReturn(snapshot);
    }

    private static ArticleSnapshot draft(int version) {
        return new ArticleSnapshot(1L, 2L, 3L, ArticleStatus.DRAFT, version, null);
    }
}
