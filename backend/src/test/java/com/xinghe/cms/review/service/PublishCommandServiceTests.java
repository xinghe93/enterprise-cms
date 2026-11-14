package com.xinghe.cms.review.service;

import com.xinghe.cms.common.contract.ArticleState;
import com.xinghe.cms.common.enums.ArticleAction;
import com.xinghe.cms.common.enums.ArticleStatus;
import com.xinghe.cms.common.exception.BusinessException;
import com.xinghe.cms.common.response.ResultCode;
import com.xinghe.cms.content.service.ArticleWorkflowService;
import com.xinghe.cms.review.dto.ArticleVersionRequest;
import com.xinghe.cms.review.dto.BatchArticleItemRequest;
import com.xinghe.cms.review.dto.BatchArticlesRequest;
import com.xinghe.cms.review.service.impl.PublishCommandServiceImpl;
import com.xinghe.cms.review.vo.ArticleStateVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 发布/下架命令的编排规则测试（不连数据库、不使用 Mockito）。
 *
 * <p>覆盖单个命令的 transition 参数、批量按文章 ID 升序调用但按请求顺序返回、
 * 全部参数校验与失败透传。</p>
 */
class PublishCommandServiceTests {

    private static final LocalDateTime PUBLISHED_AT = LocalDateTime.of(2026, 11, 14, 10, 0, 0, 120_000_000);

    private StubArticleWorkflowService workflow;
    private PublishCommandService service;

    @BeforeEach
    void setUp() {
        workflow = new StubArticleWorkflowService();
        service = new PublishCommandServiceImpl(workflow);
    }

    @Test
    void publishPassesVersionAndPublishAction() {
        workflow.nextState = new ArticleState(990201L, ArticleStatus.PUBLISHED, 4, PUBLISHED_AT);

        ArticleStateVO result = service.publish(990201L, new ArticleVersionRequest(3));

        assertThat(workflow.calls).containsExactly("transition:990201:3:PUBLISH");
        assertThat(result.id()).isEqualTo(990201L);
        assertThat(result.status()).isEqualTo(ArticleStatus.PUBLISHED);
        assertThat(result.version()).isEqualTo(4);
        assertThat(result.publishedAt()).isEqualTo(PUBLISHED_AT);
    }

    @Test
    void offlinePassesVersionAndOfflineAction() {
        workflow.nextState = new ArticleState(990201L, ArticleStatus.OFFLINE, 5, PUBLISHED_AT);

        ArticleStateVO result = service.offline(990201L, new ArticleVersionRequest(4));

        assertThat(workflow.calls).containsExactly("transition:990201:4:OFFLINE");
        assertThat(result.status()).isEqualTo(ArticleStatus.OFFLINE);
        assertThat(result.version()).isEqualTo(5);
        assertThat(result.publishedAt()).isEqualTo(PUBLISHED_AT);
    }

    @Test
    void publishBatchLocksInAscendingIdOrderButReturnsRequestOrder() {
        List<ArticleStateVO> results = service.publishBatch(new BatchArticlesRequest(List.of(
                item("12", 1), item("3", 2), item("7", 0))));

        assertThat(workflow.calls).containsExactly(
                "transition:3:2:BATCH_PUBLISH",
                "transition:7:0:BATCH_PUBLISH",
                "transition:12:1:BATCH_PUBLISH");
        assertThat(results).extracting(ArticleStateVO::id).containsExactly(12L, 3L, 7L);
        assertThat(results).extracting(ArticleStateVO::version).containsExactly(2, 3, 1);
        assertThat(results).extracting(ArticleStateVO::status)
                .containsOnly(ArticleStatus.PUBLISHED);
    }

    @Test
    void offlineBatchUsesBatchOfflineAction() {
        List<ArticleStateVO> results = service.offlineBatch(new BatchArticlesRequest(List.of(item("9", 1))));

        assertThat(workflow.calls).containsExactly("transition:9:1:BATCH_OFFLINE");
        assertThat(results).extracting(ArticleStateVO::status).containsExactly(ArticleStatus.OFFLINE);
        assertThat(results).extracting(ArticleStateVO::version).containsExactly(2);
    }

    @Test
    void rejectsEmptyBatchBeforeCallingWorkflow() {
        assertThatThrownBy(() -> service.publishBatch(new BatchArticlesRequest(List.of())))
                .isInstanceOf(BusinessException.class)
                .hasMessage("文章列表不能为空");
        assertThat(workflow.calls).isEmpty();
    }

    @Test
    void rejectsBatchLargerThanOneHundredBeforeParsingItems() {
        List<BatchArticleItemRequest> items = IntStream.rangeClosed(1, 101)
                .mapToObj(index -> item(String.valueOf(index), 0))
                .toList();

        assertThatThrownBy(() -> service.publishBatch(new BatchArticlesRequest(items)))
                .isInstanceOf(BusinessException.class)
                .hasMessage("批量数量不能超过 100");
        assertThat(workflow.calls).isEmpty();
    }

    @Test
    void rejectsDuplicateArticleIdsBeforeCallingWorkflow() {
        assertThatThrownBy(() -> service.offlineBatch(new BatchArticlesRequest(List.of(
                item("5", 0), item("5", 0)))))
                .isInstanceOf(BusinessException.class)
                .hasMessage("文章 ID 不能重复");
        assertThat(workflow.calls).isEmpty();
    }

    @Test
    void rejectsNonCanonicalArticleIdsBeforeCallingWorkflow() {
        assertThatThrownBy(() -> service.publishBatch(new BatchArticlesRequest(List.of(item("001", 0)))))
                .isInstanceOf(BusinessException.class)
                .hasMessage("请求参数错误");
        assertThatThrownBy(() -> service.publishBatch(new BatchArticlesRequest(List.of(item("0", 0)))))
                .isInstanceOf(BusinessException.class)
                .hasMessage("请求参数错误");
        assertThatThrownBy(() -> service.publishBatch(new BatchArticlesRequest(List.of(
                item("9223372036854775808", 0)))))
                .isInstanceOf(BusinessException.class)
                .hasMessage("请求参数错误");
        assertThat(workflow.calls).isEmpty();
    }

    @Test
    void rejectsMissingAndNegativeVersion() {
        assertThatThrownBy(() -> service.publish(1L, new ArticleVersionRequest(null)))
                .isInstanceOf(BusinessException.class)
                .hasMessage("文章版本不能为空");
        assertThatThrownBy(() -> service.offline(1L, new ArticleVersionRequest(-1)))
                .isInstanceOf(BusinessException.class)
                .hasMessage("文章版本不能为负数");
        assertThatThrownBy(() -> service.publishBatch(new BatchArticlesRequest(List.of(item("7", null)))))
                .isInstanceOf(BusinessException.class)
                .hasMessage("文章版本不能为空");
        assertThat(workflow.calls).isEmpty();
    }

    @Test
    void rejectsNonPositiveArticleId() {
        assertThatThrownBy(() -> service.publish(0L, new ArticleVersionRequest(1)))
                .isInstanceOf(BusinessException.class)
                .hasMessage("文章 ID 必须为正整数");
        assertThat(workflow.calls).isEmpty();
    }

    @Test
    void propagatesWorkflowFailure() {
        workflow.failure = new BusinessException(ResultCode.CONFLICT, "文章状态或版本已变化，请刷新后重试");

        assertThatThrownBy(() -> service.publishBatch(new BatchArticlesRequest(List.of(
                item("1", 0), item("2", 0)))))
                .isInstanceOf(BusinessException.class)
                .hasMessage("文章状态或版本已变化，请刷新后重试");
        assertThat(workflow.calls).hasSize(1);
    }

    private static BatchArticleItemRequest item(String id, Integer version) {
        return new BatchArticleItemRequest(id, version);
    }

    /**
     * 可编程的 ArticleWorkflowService 替身：记录调用顺序，可注入返回值或失败。
     */
    private static final class StubArticleWorkflowService implements ArticleWorkflowService {

        private final List<String> calls = new ArrayList<>();
        private ArticleState nextState;
        private RuntimeException failure;

        @Override
        public ArticleState transition(Long articleId, int expectedVersion, ArticleAction action) {
            calls.add("transition:" + articleId + ":" + expectedVersion + ":" + action);
            if (failure != null) {
                throw failure;
            }
            boolean batch = action == ArticleAction.BATCH_PUBLISH || action == ArticleAction.BATCH_OFFLINE;
            if (!batch) {
                return nextState;
            }
            ArticleStatus status = action == ArticleAction.BATCH_PUBLISH
                    ? ArticleStatus.PUBLISHED
                    : ArticleStatus.OFFLINE;
            return new ArticleState(articleId, status, expectedVersion + 1, PUBLISHED_AT);
        }
    }
}
