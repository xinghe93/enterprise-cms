package com.xinghe.cms.review.controller;

import com.xinghe.cms.common.exception.BusinessException;
import com.xinghe.cms.common.exception.GlobalExceptionHandler;
import com.xinghe.cms.common.response.PageResult;
import com.xinghe.cms.common.response.ResultCode;
import com.xinghe.cms.review.service.ReviewQueryService;
import com.xinghe.cms.review.vo.ReviewRecordVO;
import com.xinghe.cms.security.annotation.RequirePermission;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * 审核历史查询接口的 HTTP 契约与参数校验测试（standalone MockMvc）。
 *
 * <p>使用真实 Controller、Bean Validation 与 {@link GlobalExceptionHandler}，
 * Service 用替身隔离数据库；登录/鉴权链路（A 的过滤器 + B 的 AOP）在真实库联调中验证。</p>
 */
class ReviewQueryControllerTests {

    private static final long ARTICLE_ID = 990201L;
    private static final LocalDateTime REVIEWED_AT =
            LocalDateTime.of(2026, 11, 10, 10, 0, 0, 120_000_000);

    private StubReviewQueryService stubService;
    private MockMvc mockMvc;
    private JsonMapper jsonMapper;

    @BeforeEach
    void setUp() {
        stubService = new StubReviewQueryService();
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.setApplicationContext(new StaticApplicationContext());
        validator.afterPropertiesSet();
        mockMvc = MockMvcBuilders
                .standaloneSetup(new ReviewQueryController(stubService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .build();
        jsonMapper = JsonMapper.builder().build();
    }

    @Test
    void returnsReviewPageWithStringIdsAndLocalTimestamp() throws Exception {
        stubService.result = new PageResult<>(List.of(review(991301L, "APPROVED", null)), 1L, 1L, 10L);

        MvcResult result = performGet("/api/admin/articles/" + ARTICLE_ID + "/reviews");

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = body(result);
        assertThat(body.get("code").asInt()).isEqualTo(200);
        JsonNode data = body.get("data");
        assertThat(data.get("total").asLong()).isEqualTo(1L);
        assertThat(data.get("current").asLong()).isEqualTo(1L);
        assertThat(data.get("size").asLong()).isEqualTo(10L);
        JsonNode record = data.get("records").get(0);
        assertThat(record.get("id").isString()).isTrue();
        assertThat(record.get("id").asString()).isEqualTo("991301");
        assertThat(record.get("articleId").asString()).isEqualTo("990201");
        assertThat(record.get("reviewerId").asString()).isEqualTo("991203");
        assertThat(record.get("reviewerName").asString()).isEqualTo("演示管理员");
        assertThat(record.get("result").asString()).isEqualTo("APPROVED");
        assertThat(record.get("reviewedAt").asString()).isEqualTo("2026-11-10T10:00:00.120");
        assertThat(stubService.capturedArticleId).isEqualTo(ARTICLE_ID);
        assertThat(stubService.capturedQuery.getCurrent()).isEqualTo(1);
        assertThat(stubService.capturedQuery.getSize()).isEqualTo(10);
    }

    @Test
    void acceptsExplicitPaginationWithinLimits() throws Exception {
        MvcResult result = performGet("/api/admin/articles/" + ARTICLE_ID + "/reviews?current=2&size=50");

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(stubService.capturedQuery.getCurrent()).isEqualTo(2);
        assertThat(stubService.capturedQuery.getSize()).isEqualTo(50);
    }

    @Test
    void rejectsUnknownQueryParameterBeforeServiceIsCalled() throws Exception {
        MvcResult result = performGet("/api/admin/articles/" + ARTICLE_ID + "/reviews?unexpected=1");

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(body(result).get("message").asString()).isEqualTo("未知查询参数: unexpected");
        assertThat(stubService.calls).isZero();
    }

    @Test
    void rejectsRepeatedQueryParameterBeforeServiceIsCalled() throws Exception {
        MvcResult result = performGet("/api/admin/articles/" + ARTICLE_ID + "/reviews?current=1&current=2");

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(body(result).get("message").asString()).isEqualTo("查询参数重复传值: current");
        assertThat(stubService.calls).isZero();
    }

    @Test
    void rejectsSizeAboveLimit() throws Exception {
        MvcResult result = performGet("/api/admin/articles/" + ARTICLE_ID + "/reviews?size=101");

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(body(result).get("message").asString()).isEqualTo("每页数量必须在 1 到 100 之间");
        assertThat(stubService.calls).isZero();
    }

    @Test
    void rejectsZeroCurrent() throws Exception {
        MvcResult result = performGet("/api/admin/articles/" + ARTICLE_ID + "/reviews?current=0");

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(body(result).get("message").asString()).isEqualTo("页码必须大于零");
    }

    @Test
    void rejectsNonNumericAndLeadingZeroArticleId() throws Exception {
        MvcResult nonNumeric = performGet("/api/admin/articles/abc/reviews");
        assertThat(nonNumeric.getResponse().getStatus()).isEqualTo(400);
        assertThat(body(nonNumeric).get("message").asString()).isEqualTo("请求参数错误");

        MvcResult leadingZero = performGet("/api/admin/articles/001/reviews");
        assertThat(leadingZero.getResponse().getStatus()).isEqualTo(400);
        assertThat(stubService.calls).isZero();
    }

    @Test
    void acceptsLongMaxValueArticleId() throws Exception {
        MvcResult result = performGet("/api/admin/articles/9223372036854775807/reviews");

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(stubService.capturedArticleId).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void rejectsArticleIdExceedingLongMaxValue() throws Exception {
        MvcResult result = performGet("/api/admin/articles/9223372036854775808/reviews");

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(body(result).get("message").asString()).isEqualTo("请求参数错误");
        assertThat(stubService.calls).isZero();
    }

    @Test
    void propagatesMissingArticleNotFound() throws Exception {
        stubService.failure = new BusinessException(ResultCode.NOT_FOUND, "文章不存在");

        MvcResult result = performGet("/api/admin/articles/" + ARTICLE_ID + "/reviews");

        assertThat(result.getResponse().getStatus()).isEqualTo(404);
        assertThat(body(result).get("message").asString()).isEqualTo("文章不存在");
    }

    @Test
    void declaresReviewListPermission() throws Exception {
        RequirePermission permission = ReviewQueryController.class
                .getDeclaredMethod("reviews", String.class,
                        com.xinghe.cms.common.request.PageQuery.class,
                        jakarta.servlet.http.HttpServletRequest.class)
                .getAnnotation(RequirePermission.class);
        assertThat(permission).isNotNull();
        assertThat(permission.value()).containsExactly("review:list");
    }

    private MvcResult performGet(String path) throws Exception {
        return mockMvc.perform(get(path).accept(MediaType.APPLICATION_JSON)).andReturn();
    }

    private JsonNode body(MvcResult result) throws Exception {
        return jsonMapper.readTree(result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static ReviewRecordVO review(long id, String result, String comment) {
        ReviewRecordVO vo = new ReviewRecordVO();
        vo.setId(id);
        vo.setArticleId(ARTICLE_ID);
        vo.setReviewerId(991203L);
        vo.setReviewerName("演示管理员");
        vo.setResult(result);
        vo.setComment(comment);
        vo.setReviewedAt(REVIEWED_AT);
        return vo;
    }

    private static final class StubReviewQueryService implements ReviewQueryService {

        private PageResult<ReviewRecordVO> result = new PageResult<>(List.of(), 0L, 1L, 10L);
        private RuntimeException failure;
        private int calls;
        private Long capturedArticleId;
        private com.xinghe.cms.common.request.PageQuery capturedQuery;

        @Override
        public PageResult<ReviewRecordVO> page(long articleId,
                com.xinghe.cms.common.request.PageQuery query) {
            calls++;
            capturedArticleId = articleId;
            capturedQuery = query;
            if (failure != null) {
                throw failure;
            }
            return result;
        }
    }
}
