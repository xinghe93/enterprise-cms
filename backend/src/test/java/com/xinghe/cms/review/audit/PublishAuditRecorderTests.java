package com.xinghe.cms.review.audit;

import com.xinghe.cms.common.contract.ArticleState;
import com.xinghe.cms.common.enums.ArticleAction;
import com.xinghe.cms.common.enums.ArticleStatus;
import com.xinghe.cms.common.exception.BusinessException;
import com.xinghe.cms.common.response.ResultCode;
import com.xinghe.cms.content.service.ArticleWorkflowService;
import com.xinghe.cms.log.annotation.AuditLog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PublishAuditRecorder 单元测试：单条命令的 action/参数、批量"按 ID 升序流转但按请求顺序返回"、
 * 失败原样抛出，以及四个方法的 @AuditLog operation 与 MANDATORY 事务元数据。
 */
class PublishAuditRecorderTests {

    private static final LocalDateTime PUBLISHED_AT = LocalDateTime.of(2026, 11, 14, 10, 0, 0, 120_000_000);

    private StubArticleWorkflowService workflow;
    private PublishAuditRecorder recorder;

    @BeforeEach
    void setUp() {
        workflow = new StubArticleWorkflowService();
        recorder = new PublishAuditRecorder(workflow);
    }

    @Test
    void publishTransitionsWithPublishAction() {
        ArticleState state = recorder.publish(990201L, 3);

        assertThat(workflow.calls).containsExactly("transition:990201:3:PUBLISH");
        assertThat(state.id()).isEqualTo(990201L);
        assertThat(state.status()).isEqualTo(ArticleStatus.PUBLISHED);
        assertThat(state.version()).isEqualTo(4);
    }

    @Test
    void offlineTransitionsWithOfflineAction() {
        ArticleState state = recorder.offline(990202L, 5);

        assertThat(workflow.calls).containsExactly("transition:990202:5:OFFLINE");
        assertThat(state.status()).isEqualTo(ArticleStatus.OFFLINE);
        assertThat(state.version()).isEqualTo(6);
    }

    @Test
    void batchTransitionsInAscendingIdOrderButReturnsRequestOrder() {
        List<Long> ids = List.of(12L, 3L, 7L);
        List<Integer> versions = List.of(1, 2, 0);

        List<ArticleState> states = recorder.publishBatch(ids, versions);

        assertThat(workflow.calls).containsExactly(
                "transition:3:2:BATCH_PUBLISH",
                "transition:7:0:BATCH_PUBLISH",
                "transition:12:1:BATCH_PUBLISH");
        assertThat(states).extracting(ArticleState::id).containsExactly(12L, 3L, 7L);
        assertThat(states).extracting(ArticleState::version).containsExactly(2, 3, 1);
    }

    @Test
    void offlineBatchUsesOfflineBatchAction() {
        List<ArticleState> states = recorder.offlineBatch(List.of(9L, 4L), List.of(0, 1));

        assertThat(workflow.calls).containsExactly(
                "transition:4:1:BATCH_OFFLINE",
                "transition:9:0:BATCH_OFFLINE");
        assertThat(states).extracting(ArticleState::id).containsExactly(9L, 4L);
    }

    @Test
    void propagatesWorkflowFailure() {
        workflow.failure = new BusinessException(ResultCode.CONFLICT, "文章状态或版本已变化，请刷新后重试");

        assertThatThrownBy(() -> recorder.publishBatch(List.of(3L, 1L), List.of(0, 0)))
                .isInstanceOf(BusinessException.class)
                .hasMessage("文章状态或版本已变化，请刷新后重试");
        // 升序先处理 id=1，失败后不再继续
        assertThat(workflow.calls).containsExactly("transition:1:0:BATCH_PUBLISH");
    }

    @Test
    void declaresAuditOperationsAndMandatoryTransactions() throws Exception {
        assertAuditAnnotation("publish", "article.publish");
        assertAuditAnnotation("offline", "article.offline");
        assertAuditAnnotation("publishBatch", "article.publish-batch");
        assertAuditAnnotation("offlineBatch", "article.offline-batch");
    }

    private static void assertAuditAnnotation(String methodName, String operation) throws Exception {
        Method method = findMethod(methodName);
        AuditLog auditLog = method.getAnnotation(AuditLog.class);
        assertThat(auditLog).as("@AuditLog on " + methodName).isNotNull();
        assertThat(auditLog.operation()).isEqualTo(operation);
        Transactional transactional = method.getAnnotation(Transactional.class);
        assertThat(transactional).as("@Transactional on " + methodName).isNotNull();
        assertThat(transactional.propagation()).isEqualTo(Propagation.MANDATORY);
    }

    private static Method findMethod(String methodName) {
        for (Method method : PublishAuditRecorder.class.getDeclaredMethods()) {
            if (method.getName().equals(methodName)) {
                return method;
            }
        }
        throw new AssertionError("未找到方法: " + methodName);
    }

    private static final class StubArticleWorkflowService implements ArticleWorkflowService {

        private final List<String> calls = new ArrayList<>();
        private RuntimeException failure;

        @Override
        public ArticleState transition(Long articleId, int expectedVersion, ArticleAction action) {
            calls.add("transition:" + articleId + ":" + expectedVersion + ":" + action.name());
            if (failure != null) {
                throw failure;
            }
            boolean batch = action == ArticleAction.BATCH_PUBLISH || action == ArticleAction.BATCH_OFFLINE;
            ArticleStatus status = switch (action) {
                case PUBLISH, BATCH_PUBLISH -> ArticleStatus.PUBLISHED;
                case OFFLINE, BATCH_OFFLINE -> ArticleStatus.OFFLINE;
                default -> throw new IllegalStateException("unexpected action: " + action);
            };
            return new ArticleState(articleId, status, expectedVersion + 1, PUBLISHED_AT);
        }
    }
}
