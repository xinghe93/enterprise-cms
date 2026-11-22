package com.xinghe.cms.review.audit;

import com.xinghe.cms.common.contract.ArticleState;
import com.xinghe.cms.common.contract.TagView;
import com.xinghe.cms.common.enums.ArticleAction;
import com.xinghe.cms.common.enums.ArticleStatus;
import com.xinghe.cms.common.exception.BusinessException;
import com.xinghe.cms.common.response.ResultCode;
import com.xinghe.cms.content.service.ArticleWorkflowService;
import com.xinghe.cms.log.annotation.AuditLog;
import com.xinghe.cms.review.service.ArticleTagService;
import com.xinghe.cms.review.vo.ArticleTagsVO;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ArticleTagsAuditRecorderTests {

    private static final long ARTICLE_ID = 990201L;

    private final StubWorkflowService workflowService = new StubWorkflowService();
    private final StubArticleTagService articleTagService = new StubArticleTagService();
    private final ArticleTagsAuditRecorder recorder =
            new ArticleTagsAuditRecorder(workflowService, articleTagService);

    @Test
    void bindsTagsAfterTransitionAndReturnsSortedTagIds() {
        workflowService.result = new ArticleState(ARTICLE_ID, ArticleStatus.REJECTED, 4, null);

        ArticleTagsVO result = recorder.bind(ARTICLE_ID, 3, List.of(9902L, 9901L));

        assertThat(workflowService.calls).containsExactly("transition:990201:3:BIND_TAGS");
        assertThat(articleTagService.replacedTagIds).containsExactly(9902L, 9901L);
        assertThat(result.id()).isEqualTo(ARTICLE_ID);
        assertThat(result.status()).isEqualTo(ArticleStatus.REJECTED);
        assertThat(result.version()).isEqualTo(4);
        assertThat(result.tagIds()).containsExactly("9901", "9902");
    }

    @Test
    void doesNotReplaceBindingsWhenTransitionFails() {
        workflowService.failure = new BusinessException(ResultCode.CONFLICT, "文章状态或版本已变化，请刷新后重试");

        assertThatThrownBy(() -> recorder.bind(ARTICLE_ID, 3, List.of(9901L)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("版本已变化");
        assertThat(articleTagService.replaceCalls).isZero();
    }

    @Test
    void declaresAuditOperationAndMandatoryTransaction() throws Exception {
        AuditLog auditLog = ArticleTagsAuditRecorder.class
                .getMethod("bind", long.class, int.class, List.class)
                .getAnnotation(AuditLog.class);
        assertThat(auditLog).isNotNull();
        assertThat(auditLog.operation()).isEqualTo("article.bind-tag");

        Transactional transactional = ArticleTagsAuditRecorder.class
                .getMethod("bind", long.class, int.class, List.class)
                .getAnnotation(Transactional.class);
        assertThat(transactional).isNotNull();
        assertThat(transactional.propagation()).isEqualTo(Propagation.MANDATORY);
    }

    private static final class StubWorkflowService implements ArticleWorkflowService {

        private final List<String> calls = new ArrayList<>();
        private ArticleState result;
        private RuntimeException failure;

        @Override
        public ArticleState transition(Long articleId, int expectedVersion, ArticleAction action) {
            calls.add("transition:" + articleId + ":" + expectedVersion + ":" + action.name());
            if (failure != null) {
                throw failure;
            }
            return result;
        }
    }

    private static final class StubArticleTagService implements ArticleTagService {

        private final List<Long> replacedTagIds = new ArrayList<>();
        private int replaceCalls;

        @Override
        public void replaceBindings(Long articleId, List<Long> tagIds) {
            replaceCalls++;
            replacedTagIds.clear();
            replacedTagIds.addAll(tagIds);
        }

        @Override
        public Map<Long, List<TagView>> findByArticleIds(List<Long> articleIds) {
            return Map.of();
        }
    }
}
