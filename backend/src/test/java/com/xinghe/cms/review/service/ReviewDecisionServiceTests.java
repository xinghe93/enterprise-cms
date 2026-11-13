package com.xinghe.cms.review.service;

import java.lang.reflect.Proxy;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.xinghe.cms.common.contract.ArticleState;
import com.xinghe.cms.common.contract.CurrentUser;
import com.xinghe.cms.common.enums.ArticleAction;
import com.xinghe.cms.common.enums.ArticleStatus;
import com.xinghe.cms.common.exception.BusinessException;
import com.xinghe.cms.common.response.ResultCode;
import com.xinghe.cms.content.service.ArticleWorkflowService;
import com.xinghe.cms.review.dto.ReviewDecisionRequest;
import com.xinghe.cms.review.entity.ReviewRecord;
import com.xinghe.cms.review.mapper.ReviewRecordMapper;
import com.xinghe.cms.review.service.impl.ReviewDecisionServiceImpl;
import com.xinghe.cms.review.vo.ArticleStateVO;
import com.xinghe.cms.security.CurrentUserProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ReviewDecisionServiceImpl 单元测试：用 JDK 动态代理代替 Mockito（本机 JDK 25 下
 * MockMaker 不可用），用真实 {@link CurrentUserProvider} 读取请求属性中的身份。
 */
class ReviewDecisionServiceTests {

    private static final long ARTICLE_ID = 990201L;
    private static final long REVIEWER_ID = 991202L;
    private static final int VERSION = 3;

    private ReviewDecisionService service;
    private StubReviewRecordMapper mapper;
    private StubArticleWorkflowService workflow;

    @BeforeEach
    void setUp() {
        mapper = new StubReviewRecordMapper();
        workflow = new StubArticleWorkflowService();
        service = new ReviewDecisionServiceImpl(mapper.proxy(), workflow, new CurrentUserProvider());
        bindCurrentUser(REVIEWER_ID);
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void approveTransitionsArticleAndWritesApprovedReview() {
        workflow.result = new ArticleState(ARTICLE_ID, ArticleStatus.APPROVED, VERSION + 1,
                LocalDateTime.of(2026, 11, 13, 10, 0, 0, 120_000_000));

        ArticleStateVO result = service.approve(ARTICLE_ID, new ReviewDecisionRequest(VERSION, "内容完整"));

        assertThat(workflow.calls).containsExactly("transition:" + ARTICLE_ID + ":" + VERSION + ":APPROVE");
        ReviewRecord record = mapper.inserted.get();
        assertThat(record.getArticleId()).isEqualTo(ARTICLE_ID);
        assertThat(record.getReviewerId()).isEqualTo(REVIEWER_ID);
        assertThat(record.getResult()).isEqualTo(ReviewRecord.RESULT_APPROVED);
        assertThat(record.getComment()).isEqualTo("内容完整");
        assertThat(record.getReviewedAt()).isNull();
        assertThat(result.id()).isEqualTo(ARTICLE_ID);
        assertThat(result.status()).isEqualTo(ArticleStatus.APPROVED);
        assertThat(result.version()).isEqualTo(VERSION + 1);
        assertThat(result.publishedAt())
                .isEqualTo(LocalDateTime.of(2026, 11, 13, 10, 0, 0, 120_000_000));
    }

    @Test
    void approveStoresBlankCommentAsNull() {
        service.approve(ARTICLE_ID, new ReviewDecisionRequest(VERSION, "   "));

        assertThat(mapper.inserted.get().getComment()).isNull();
    }

    @Test
    void rejectWritesTrimmedRejectedReview() {
        service.reject(ARTICLE_ID, new ReviewDecisionRequest(VERSION, "  内容不完整  "));

        assertThat(workflow.calls).containsExactly("transition:" + ARTICLE_ID + ":" + VERSION + ":REJECT");
        ReviewRecord record = mapper.inserted.get();
        assertThat(record.getResult()).isEqualTo(ReviewRecord.RESULT_REJECTED);
        assertThat(record.getComment()).isEqualTo("内容不完整");
    }

    @Test
    void rejectRequiresNonBlankCommentAndSkipsWorkflow() {
        List<ReviewDecisionRequest> requests = List.of(
                new ReviewDecisionRequest(VERSION, null),
                new ReviewDecisionRequest(VERSION, "  "));

        for (ReviewDecisionRequest request : requests) {
            assertThatThrownBy(() -> service.reject(ARTICLE_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("驳回意见不能为空");
        }
        assertThat(workflow.calls).isEmpty();
        assertThat(mapper.inserted.get()).isNull();
    }

    @Test
    void rejectsNonPositiveArticleIdBeforeCallingWorkflow() {
        for (long articleId : new long[]{0L, -3L}) {
            assertThatThrownBy(() -> service.approve(articleId, new ReviewDecisionRequest(VERSION, null)))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("文章 ID 必须为正整数");
        }
        assertThat(workflow.calls).isEmpty();
    }

    @Test
    void rejectsMissingAndNegativeVersionBeforeCallingWorkflow() {
        assertThatThrownBy(() -> service.approve(ARTICLE_ID, new ReviewDecisionRequest(null, null)))
                .isInstanceOf(BusinessException.class)
                .hasMessage("文章版本不能为空");
        assertThatThrownBy(() -> service.approve(ARTICLE_ID, new ReviewDecisionRequest(-1, null)))
                .isInstanceOf(BusinessException.class)
                .hasMessage("文章版本不能为负数");
        assertThat(workflow.calls).isEmpty();
    }

    @Test
    void propagatesWorkflowConflictAndSkipsHistory() {
        workflow.failure = new BusinessException(ResultCode.CONFLICT, "文章状态或版本已变化，请刷新后重试");

        assertThatThrownBy(() -> service.approve(ARTICLE_ID, new ReviewDecisionRequest(VERSION, null)))
                .isInstanceOf(BusinessException.class)
                .hasMessage("文章状态或版本已变化，请刷新后重试");
        assertThat(mapper.inserted.get()).isNull();
    }

    private void bindCurrentUser(long userId) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(CurrentUserProvider.REQUEST_ATTRIBUTE, new CurrentUser(userId, "it_reviewer", null));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    /** 真实的 ArticleWorkflowService 桩：记录调用参数并返回固定状态或抛指定异常。 */
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

    /** ReviewRecordMapper 的动态代理桩：只处理 insert 与 Object 方法。 */
    private static final class StubReviewRecordMapper {

        private final AtomicReference<ReviewRecord> inserted = new AtomicReference<>();

        ReviewRecordMapper proxy() {
            return (ReviewRecordMapper) Proxy.newProxyInstance(
                    ReviewRecordMapper.class.getClassLoader(),
                    new Class<?>[]{ReviewRecordMapper.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "insert" -> {
                            if (args[0] instanceof ReviewRecord record) {
                                inserted.set(record);
                            }
                            yield 1;
                        }
                        case "toString" -> "ReviewRecordMapperStub";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
        }
    }
}
