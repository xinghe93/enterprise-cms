package com.xinghe.cms.review.audit;

import com.xinghe.cms.common.contract.ArticleState;
import com.xinghe.cms.common.contract.CurrentUser;
import com.xinghe.cms.common.enums.ArticleAction;
import com.xinghe.cms.common.enums.ArticleStatus;
import com.xinghe.cms.common.exception.BusinessException;
import com.xinghe.cms.common.response.ResultCode;
import com.xinghe.cms.content.service.ArticleWorkflowService;
import com.xinghe.cms.log.annotation.AuditLog;
import com.xinghe.cms.review.entity.ReviewRecord;
import com.xinghe.cms.review.mapper.ReviewRecordMapper;
import com.xinghe.cms.security.CurrentUserProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * ReviewAuditRecorder 单元测试（JDK 动态代理代替 Mockito）。
 *
 * <p>覆盖：状态流转参数、审核记录字段（审核人取自安全入口、reviewedAt 由数据库生成）、
 * 未认证 401 不产生写入、历史插入 0 行即失败，以及 @AuditLog / MANDATORY 事务元数据。</p>
 */
class ReviewAuditRecorderTests {

    private static final long ARTICLE_ID = 990201L;
    private static final long REVIEWER_ID = 991202L;
    private static final int VERSION = 3;

    private ReviewAuditRecorder recorder;
    private StubReviewRecordMapper mapper;
    private StubArticleWorkflowService workflow;

    @BeforeEach
    void setUp() {
        mapper = new StubReviewRecordMapper();
        workflow = new StubArticleWorkflowService();
        recorder = new ReviewAuditRecorder(workflow, mapper.proxy(), new CurrentUserProvider());
        bindCurrentUser(REVIEWER_ID);
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void approveTransitionsAndWritesApprovedRecord() {
        workflow.result = new ArticleState(ARTICLE_ID, ArticleStatus.APPROVED, VERSION + 1,
                LocalDateTime.of(2026, 11, 13, 10, 0, 0, 120_000_000));

        ArticleState state = recorder.approve(ARTICLE_ID, VERSION, "内容完整");

        assertThat(workflow.calls).containsExactly("transition:" + ARTICLE_ID + ":" + VERSION + ":APPROVE");
        ReviewRecord record = mapper.inserted.get();
        assertThat(record).isNotNull();
        assertThat(record.getArticleId()).isEqualTo(ARTICLE_ID);
        assertThat(record.getReviewerId()).isEqualTo(REVIEWER_ID);
        assertThat(record.getResult()).isEqualTo(ReviewRecord.RESULT_APPROVED);
        assertThat(record.getComment()).isEqualTo("内容完整");
        assertThat(record.getReviewedAt()).isNull();
        assertThat(state.status()).isEqualTo(ArticleStatus.APPROVED);
        assertThat(state.version()).isEqualTo(VERSION + 1);
    }

    @Test
    void rejectWritesRejectedRecord() {
        recorder.reject(ARTICLE_ID, VERSION, "内容不完整");

        assertThat(workflow.calls).containsExactly("transition:" + ARTICLE_ID + ":" + VERSION + ":REJECT");
        ReviewRecord record = mapper.inserted.get();
        assertThat(record.getResult()).isEqualTo(ReviewRecord.RESULT_REJECTED);
        assertThat(record.getComment()).isEqualTo("内容不完整");
        assertThat(record.getReviewerId()).isEqualTo(REVIEWER_ID);
    }

    @Test
    void unauthenticatedRejectsBeforeAnyWrite() {
        RequestContextHolder.resetRequestAttributes();

        try {
            recorder.approve(ARTICLE_ID, VERSION, null);
            fail("未认证时应抛 401");
        } catch (BusinessException exception) {
            assertThat(exception.getResultCode()).isEqualTo(ResultCode.UNAUTHORIZED);
        }
        assertThat(workflow.calls).isEmpty();
        assertThat(mapper.inserted.get()).isNull();
    }

    @Test
    void failsWhenHistoryInsertDoesNotWriteRow() {
        mapper.insertResult = 0;

        assertThatThrownBy(() -> recorder.approve(ARTICLE_ID, VERSION, "内容完整"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("审核记录写入失败");
        assertThat(workflow.calls).containsExactly("transition:" + ARTICLE_ID + ":" + VERSION + ":APPROVE");
    }

    @Test
    void declaresAuditOperationsAndMandatoryTransactions() throws Exception {
        assertAuditAnnotation("approve", "article.approve");
        assertAuditAnnotation("reject", "article.reject");
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
        for (Method method : ReviewAuditRecorder.class.getDeclaredMethods()) {
            if (method.getName().equals(methodName)) {
                return method;
            }
        }
        throw new AssertionError("未找到方法: " + methodName);
    }

    private static void bindCurrentUser(long userId) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(CurrentUserProvider.REQUEST_ATTRIBUTE, new CurrentUser(userId, "it_reviewer", null));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    private static final class StubArticleWorkflowService implements ArticleWorkflowService {

        private final List<String> calls = new ArrayList<>();
        private ArticleState result = new ArticleState(ARTICLE_ID, ArticleStatus.APPROVED, VERSION + 1, null);
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

    private static final class StubReviewRecordMapper {

        private final AtomicReference<ReviewRecord> inserted = new AtomicReference<>();
        private int insertResult = 1;

        ReviewRecordMapper proxy() {
            return (ReviewRecordMapper) Proxy.newProxyInstance(
                    ReviewRecordMapper.class.getClassLoader(),
                    new Class<?>[]{ReviewRecordMapper.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "insert" -> {
                            if (args[0] instanceof ReviewRecord record) {
                                inserted.set(record);
                            }
                            yield insertResult;
                        }
                        case "toString" -> "ReviewRecordMapperStub";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
        }
    }
}
