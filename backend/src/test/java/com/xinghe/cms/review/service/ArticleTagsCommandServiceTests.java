package com.xinghe.cms.review.service;

import com.xinghe.cms.common.contract.ArticleState;
import com.xinghe.cms.common.contract.TagView;
import com.xinghe.cms.common.enums.ArticleAction;
import com.xinghe.cms.common.enums.ArticleStatus;
import com.xinghe.cms.common.exception.BusinessException;
import com.xinghe.cms.content.service.ArticleWorkflowService;
import com.xinghe.cms.review.audit.ArticleTagsAuditRecorder;
import com.xinghe.cms.review.dto.ArticleTagsUpdateRequest;
import com.xinghe.cms.review.service.impl.ArticleTagsCommandServiceImpl;
import com.xinghe.cms.review.vo.ArticleTagsVO;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ArticleTagsCommandServiceTests {

    private static final long ARTICLE_ID = 990201L;

    private final StubWorkflowService workflowService = new StubWorkflowService();
    private final StubArticleTagService articleTagService = new StubArticleTagService();
    private final ArticleTagsCommandService service = new ArticleTagsCommandServiceImpl(
            new ArticleTagsAuditRecorder(workflowService, articleTagService));

    @Test
    void bindsTagsWithParsedIdsAndReturnsResult() {
        workflowService.result = new ArticleState(ARTICLE_ID, ArticleStatus.REJECTED, 4, null);

        ArticleTagsVO result = service.update(ARTICLE_ID, new ArticleTagsUpdateRequest(3, List.of("9902", "9901")));

        assertThat(workflowService.calls).containsExactly("transition:990201:3:BIND_TAGS");
        assertThat(articleTagService.replacedTagIds).containsExactly(9902L, 9901L);
        assertThat(result.tagIds()).containsExactly("9901", "9902");
    }

    @Test
    void rejectsNullRequestBeforeTouchingWorkflow() {
        assertThatThrownBy(() -> service.update(ARTICLE_ID, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("请求体不能为空");
        assertThat(workflowService.calls).isEmpty();
    }

    @Test
    void rejectsNonPositiveArticleId() {
        assertThatThrownBy(() -> service.update(0, new ArticleTagsUpdateRequest(0, List.of())))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("文章 ID 必须为正整数");
    }

    @Test
    void rejectsNullVersion() {
        assertThatThrownBy(() -> service.update(ARTICLE_ID, new ArticleTagsUpdateRequest(null, List.of())))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("文章版本不能为空");
    }

    @Test
    void rejectsNegativeVersion() {
        assertThatThrownBy(() -> service.update(ARTICLE_ID, new ArticleTagsUpdateRequest(-1, List.of())))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("文章版本不能为负数");
    }

    @Test
    void rejectsNullTagIds() {
        assertThatThrownBy(() -> service.update(ARTICLE_ID, new ArticleTagsUpdateRequest(0, null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("标签 ID 列表不能为空");
    }

    @Test
    void rejectsMalformedTagIdBeforeTouchingWorkflow() {
        assertThatThrownBy(() -> service.update(ARTICLE_ID, new ArticleTagsUpdateRequest(0, List.of("0x1"))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("资源 ID 格式不正确");
        assertThat(workflowService.calls).isEmpty();
        assertThat(articleTagService.replaceCalls).isZero();
    }

    @Test
    void rejectsDuplicateTagIdsBeforeTouchingWorkflow() {
        assertThatThrownBy(() -> service.update(ARTICLE_ID,
                new ArticleTagsUpdateRequest(0, List.of("9901", "9901"))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("标签 ID 不能重复");
        assertThat(workflowService.calls).isEmpty();
        assertThat(articleTagService.replaceCalls).isZero();
    }

    @Test
    void rejectsDuplicateTagIdsBeforeVersionCheckEvenWhenVersionIsStale() {
        assertThatThrownBy(() -> service.update(ARTICLE_ID,
                new ArticleTagsUpdateRequest(99, List.of("9901", "9901"))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("标签 ID 不能重复");
        assertThat(workflowService.calls).isEmpty();
        assertThat(articleTagService.replaceCalls).isZero();
    }

    private static final class StubWorkflowService implements ArticleWorkflowService {

        private final List<String> calls = new ArrayList<>();
        private ArticleState result;

        @Override
        public ArticleState transition(Long articleId, int expectedVersion, ArticleAction action) {
            calls.add("transition:" + articleId + ":" + expectedVersion + ":" + action.name());
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
