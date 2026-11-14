package com.xinghe.cms.review.controller;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

import com.xinghe.cms.common.enums.ArticleStatus;
import com.xinghe.cms.common.exception.GlobalExceptionHandler;
import com.xinghe.cms.review.dto.ReviewDecisionRequest;
import com.xinghe.cms.review.service.ReviewDecisionService;
import com.xinghe.cms.review.vo.ArticleStateVO;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 审核裁决接口的 HTTP 契约、参数校验与统一异常响应测试。
 *
 * <p>使用 standalone MockMvc 装配真实 Controller、Bean Validation 与
 * {@link GlobalExceptionHandler}；不启动完整 Spring 上下文，避免 A 的
 * BearerTokenAuthenticationFilter 拦截测试请求（无 Token 会直接 401），
 * 登录/鉴权链路由真实库联调验证。Service 用可配置替身隔离数据库。</p>
 */
class ReviewDecisionControllerTests {

    private static final long ARTICLE_ID = 990201L;

    private StubReviewDecisionService stubService;
    private MockMvc mockMvc;
    private JsonMapper jsonMapper;

    @BeforeEach
    void setUp() {
        stubService = new StubReviewDecisionService();
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.setApplicationContext(new StaticApplicationContext());
        validator.afterPropertiesSet();
        mockMvc = MockMvcBuilders
                .standaloneSetup(new ReviewDecisionController(stubService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .build();
        jsonMapper = JsonMapper.builder().build();
    }

    @Test
    void approveReturnsArticleStateWithStringIdAndLocalTimestamp() throws Exception {
        stubService.result = new ArticleStateVO(ARTICLE_ID, ArticleStatus.APPROVED, 4,
                LocalDateTime.of(2026, 11, 13, 10, 0, 0, 120_000_000));

        MvcResult result = postDecision("/api/admin/articles/" + ARTICLE_ID + "/approve",
                "{\"version\": 3, \"comment\": \"内容完整\"}");

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = body(result);
        assertThat(body.get("code").asInt()).isEqualTo(200);
        JsonNode data = body.get("data");
        assertThat(data.get("id").isString()).isTrue();
        assertThat(data.get("id").asString()).isEqualTo("990201");
        assertThat(data.get("status").asString()).isEqualTo("APPROVED");
        assertThat(data.get("version").asInt()).isEqualTo(4);
        assertThat(data.get("publishedAt").asString()).isEqualTo("2026-11-13T10:00:00.120");
        assertThat(stubService.approvedArticleId).isEqualTo(ARTICLE_ID);
        assertThat(stubService.approvedRequest.version()).isEqualTo(3);
        assertThat(stubService.approvedRequest.comment()).isEqualTo("内容完整");
    }

    @Test
    void rejectReturnsRejectedState() throws Exception {
        stubService.result = new ArticleStateVO(ARTICLE_ID, ArticleStatus.REJECTED, 4, null);

        MvcResult result = postDecision("/api/admin/articles/" + ARTICLE_ID + "/reject",
                "{\"version\": 3, \"comment\": \"内容不完整\"}");

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode data = body(result).get("data");
        assertThat(data.get("status").asString()).isEqualTo("REJECTED");
        assertThat(data.get("publishedAt").isNull()).isTrue();
        assertThat(stubService.rejectedArticleId).isEqualTo(ARTICLE_ID);
        assertThat(stubService.rejectedRequest.comment()).isEqualTo("内容不完整");
    }

    @Test
    void rejectsMissingVersionBeforeServiceIsCalled() throws Exception {
        MvcResult result = postDecision("/api/admin/articles/" + ARTICLE_ID + "/approve",
                "{\"comment\": \"内容完整\"}");

        assertError(result, 400, "文章版本不能为空");
        assertThat(stubService.approvedArticleId).isNull();
    }

    @Test
    void rejectsNegativeVersionBeforeServiceIsCalled() throws Exception {
        MvcResult result = postDecision("/api/admin/articles/" + ARTICLE_ID + "/approve",
                "{\"version\": -1}");

        assertError(result, 400, "文章版本不能为负数");
        assertThat(stubService.approvedArticleId).isNull();
    }

    @Test
    void rejectsOversizedCommentBeforeServiceIsCalled() throws Exception {
        MvcResult result = postDecision("/api/admin/articles/" + ARTICLE_ID + "/reject",
                "{\"version\": 3, \"comment\": \"" + "好".repeat(1001) + "\"}");

        assertError(result, 400, "审核意见不能超过 1000 字");
        assertThat(stubService.rejectedArticleId).isNull();
    }

    @Test
    void rejectsNonNumericArticleIdWithUnifiedBadRequest() throws Exception {
        MvcResult result = postDecision("/api/admin/articles/abc/approve", "{\"version\": 3}");

        assertError(result, 400, "请求参数错误");
        assertThat(stubService.approvedArticleId).isNull();
    }

    @Test
    void rejectsHexadecimalArticleIdWithUnifiedBadRequest() throws Exception {
        MvcResult result = postDecision("/api/admin/articles/0x1/approve", "{\"version\": 3}");

        assertError(result, 400, "请求参数错误");
        assertThat(stubService.approvedArticleId).isNull();
    }

    @Test
    void acceptsEighteenDigitArticleId() throws Exception {
        stubService.result = new ArticleStateVO(123L, ArticleStatus.APPROVED, 1, null);

        MvcResult result = postDecision("/api/admin/articles/999999999999999999/approve",
                "{\"version\": 0}");

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(stubService.approvedArticleId).isEqualTo(999999999999999999L);
    }

    @Test
    void acceptsLongMaxValueArticleId() throws Exception {
        stubService.result = new ArticleStateVO(Long.MAX_VALUE, ArticleStatus.APPROVED, 1, null);

        MvcResult result = postDecision("/api/admin/articles/9223372036854775807/approve",
                "{\"version\": 0}");

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(stubService.approvedArticleId).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void rejectsArticleIdExceedingLongMaxValue() throws Exception {
        MvcResult result = postDecision("/api/admin/articles/9223372036854775808/approve",
                "{\"version\": 3}");

        assertError(result, 400, "请求参数错误");
        assertThat(stubService.approvedArticleId).isNull();
    }

    @Test
    void rejectsArticleIdWithLeadingZeros() throws Exception {
        MvcResult result = postDecision("/api/admin/articles/001/approve", "{\"version\": 3}");

        assertError(result, 400, "请求参数错误");
        assertThat(stubService.approvedArticleId).isNull();
    }

    @Test
    void rejectsZeroArticleId() throws Exception {
        MvcResult result = postDecision("/api/admin/articles/0/reject", "{\"version\": 3}");

        assertError(result, 400, "请求参数错误");
        assertThat(stubService.rejectedArticleId).isNull();
    }

    private MvcResult postDecision(String path, String json) throws Exception {
        return mockMvc.perform(post(path)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json)).andReturn();
    }

    private JsonNode body(MvcResult result) throws Exception {
        return jsonMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private void assertError(MvcResult result, int status, String message) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(status);
        assertThat(result.getResponse().getContentType()).startsWith(MediaType.APPLICATION_JSON_VALUE);
        JsonNode json = body(result);
        assertThat(json.size()).isEqualTo(4);
        assertThat(json.get("code").asInt()).isEqualTo(status);
        assertThat(json.get("data").isNull()).isTrue();
        assertThat(json.get("message").asString()).isEqualTo(message);
        assertThat(json.get("timestamp").asString()).endsWith("Z");
    }

    /** 可配置的 Service 替身，用于隔离 HTTP 层与业务实现。 */
    private static final class StubReviewDecisionService implements ReviewDecisionService {

        private ArticleStateVO result = new ArticleStateVO(ARTICLE_ID, ArticleStatus.APPROVED, 1, null);
        private Long approvedArticleId;
        private Long rejectedArticleId;
        private ReviewDecisionRequest approvedRequest;
        private ReviewDecisionRequest rejectedRequest;

        @Override
        public ArticleStateVO approve(long articleId, ReviewDecisionRequest request) {
            approvedArticleId = articleId;
            approvedRequest = request;
            return result;
        }

        @Override
        public ArticleStateVO reject(long articleId, ReviewDecisionRequest request) {
            rejectedArticleId = articleId;
            rejectedRequest = request;
            return result;
        }
    }
}
