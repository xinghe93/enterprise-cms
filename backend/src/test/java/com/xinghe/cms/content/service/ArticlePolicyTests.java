package com.xinghe.cms.content.service;

import com.xinghe.cms.common.contract.AuthorizationSnapshot;
import com.xinghe.cms.common.enums.ArticleAction;
import com.xinghe.cms.common.enums.ArticleStatus;
import com.xinghe.cms.common.exception.BusinessException;
import com.xinghe.cms.common.response.ResultCode;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ArticlePolicyTests {

    @Test
    void customReviewerCanReadOtherNonDraftsButCannotReadOtherDrafts() {
        var scope = ArticlePolicy.readScope(new AuthorizationSnapshot(Set.of("CUSTOM"),
                Set.of("article:publish-batch")));

        assertThat(scope.allows(1L, 2L, ArticleStatus.REJECTED)).isTrue();
        assertThat(scope.allows(1L, 2L, ArticleStatus.DRAFT)).isFalse();
        assertThat(scope.allows(1L, 1L, ArticleStatus.DRAFT)).isTrue();
    }

    @Test
    void historyPermissionAloneDoesNotExpandArticleScope() {
        var scope = ArticlePolicy.readScope(new AuthorizationSnapshot(Set.of("CUSTOM"),
                Set.of("review:list")));

        assertThat(scope.allows(1L, 2L, ArticleStatus.PUBLISHED)).isFalse();
    }

    @Test
    void adminCannotApproveOwnArticle() {
        var admin = new AuthorizationSnapshot(Set.of("ADMIN"), Set.of("article:approve"));

        assertThatThrownBy(() -> ArticlePolicy.requireWriteScope(1L, 1L, admin, ArticleAction.APPROVE))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getResultCode()).isEqualTo(ResultCode.FORBIDDEN));
    }

    @Test
    void batchPermissionDoesNotRequireSingleArticlePermission() {
        var batchPublisher = new AuthorizationSnapshot(Set.of("CUSTOM"), Set.of("article:publish-batch"));

        ArticlePolicy.requirePermission(batchPublisher, ArticleAction.BATCH_PUBLISH);
        assertThatThrownBy(() -> ArticlePolicy.requirePermission(batchPublisher, ArticleAction.PUBLISH))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getResultCode()).isEqualTo(ResultCode.FORBIDDEN));
    }

    @Test
    void offlineEditsReturnToDraftButCannotBePublishedWithoutApproval() {
        assertThat(ArticleAction.SAVE.targetStatus(ArticleStatus.OFFLINE)).isEqualTo(ArticleStatus.DRAFT);
        assertThat(ArticleAction.BIND_TAGS.targetStatus(ArticleStatus.OFFLINE)).isEqualTo(ArticleStatus.DRAFT);
        assertThat(ArticleAction.PUBLISH.allows(ArticleStatus.OFFLINE)).isFalse();
    }
}
