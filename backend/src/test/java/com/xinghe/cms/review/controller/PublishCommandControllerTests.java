package com.xinghe.cms.review.controller;

import com.xinghe.cms.common.enums.ArticleStatus;
import com.xinghe.cms.common.exception.GlobalExceptionHandler;
import com.xinghe.cms.review.dto.ArticleVersionRequest;
import com.xinghe.cms.review.dto.BatchArticlesRequest;
import com.xinghe.cms.review.service.PublishCommandService;
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

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 发布/下架命令接口的 HTTP 契约、参数校验与统一异常响应测试。
 *
 * <p>使用 standalone MockMvc 装配真实 Controller、Bean Validation 与
 * {@link GlobalExceptionHandler}；不启动完整 Spring 上下文，避免 A 的
 * BearerTokenAuthenticationFilter 拦截测试请求（无 Token 会直接 401），
 * 登录/鉴权链路由真实库联调验证。Service 用替身隔离数据库。</p>
 */
class PublishCommandControllerTests {

    private static final long ARTICLE_ID = 990201L;
    private static final LocalDateTime PUBLISHED_AT = LocalDateTime.of(2026, 11, 14, 10, 0, 0, 120_000_000);

    private StubPublishCommandService stubService;
    private MockMvc mockMvc;
    private JsonMapper jsonMapper;

    @BeforeEach
    void setUp() {
        stubService = new StubPublishCommandService();
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.setApplicationContext(new StaticApplicationContext());
        validator.afterPropertiesSet();
        mockMvc = MockMvcBuilders
                .standaloneSetup(new PublishCommandController(stubService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .build();
        jsonMapper = JsonMapper.builder().build();
    }

    @Test
    void publishReturnsArticleStateWithStringIdAndLocalTimestamp() throws Exception {
        stubService.singleResult = new ArticleStateVO(ARTICLE_ID, ArticleStatus.PUBLISHED, 4, PUBLISHED_AT);

        MvcResult result = postJson("/api/admin/articles/" + ARTICLE_ID + "/publish", "{\"version\": 3}");

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = body(result);
        assertThat(body.get("code").asInt()).isEqualTo(200);
        JsonNode data = body.get("data");
        assertThat(data.get("id").isString()).isTrue();
        assertThat(data.get("id").asString()).isEqualTo("990201");
        assertThat(data.get("status").asString()).isEqualTo("PUBLISHED");
        assertThat(data.get("version").asInt()).isEqualTo(4);
        assertThat(data.get("publishedAt").asString()).isEqualTo("2026-11-14T10:00:00.120");
        assertThat(stubService.singleCalls).containsExactly("publish:990201:3");
    }

    @Test
    void offlineReturnsOfflineStateKeepingPublishedAt() throws Exception {
        stubService.singleResult = new ArticleStateVO(ARTICLE_ID, ArticleStatus.OFFLINE, 5, PUBLISHED_AT);

        MvcResult result = postJson("/api/admin/articles/" + ARTICLE_ID + "/offline", "{\"version\": 4}");

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode data = body(result).get("data");
        assertThat(data.get("status").asString()).isEqualTo("OFFLINE");
        assertThat(data.get("version").asInt()).isEqualTo(5);
        assertThat(data.get("publishedAt").asString()).isEqualTo("2026-11-14T10:00:00.120");
        assertThat(stubService.singleCalls).containsExactly("offline:990201:4");
    }

    @Test
    void batchPublishReturnsStatesInRequestOrder() throws Exception {
        stubService.batchResult = List.of(
                new ArticleStateVO(12L, ArticleStatus.PUBLISHED, 2, PUBLISHED_AT),
                new ArticleStateVO(3L, ArticleStatus.PUBLISHED, 3, PUBLISHED_AT),
                new ArticleStateVO(7L, ArticleStatus.PUBLISHED, 1, PUBLISHED_AT));

        MvcResult result = postJson("/api/admin/articles/batch/publish",
                batchBody(itemJson("12", 1), itemJson("3", 2), itemJson("7", 0)));

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode data = body(result).get("data");
        assertThat(data.isArray()).isTrue();
        assertThat(data.size()).isEqualTo(3);
        assertThat(data.get(0).get("id").asString()).isEqualTo("12");
        assertThat(data.get(1).get("id").asString()).isEqualTo("3");
        assertThat(data.get(2).get("id").asString()).isEqualTo("7");
        assertThat(stubService.batchCalls).containsExactly("publishBatch:3");
    }

    @Test
    void batchOfflineReturnsOfflineStates() throws Exception {
        stubService.batchResult = List.of(new ArticleStateVO(9L, ArticleStatus.OFFLINE, 2, PUBLISHED_AT));

        MvcResult result = postJson("/api/admin/articles/batch/offline",
                batchBody(itemJson("9", 1)));

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(result).get("data").get(0).get("status").asString()).isEqualTo("OFFLINE");
        assertThat(stubService.batchCalls).containsExactly("offlineBatch:1");
    }

    @Test
    void rejectsMissingVersion() throws Exception {
        MvcResult result = postJson("/api/admin/articles/" + ARTICLE_ID + "/publish", "{}");

        assertError(result, 400, "文章版本不能为空");
        assertThat(stubService.singleCalls).isEmpty();
    }

    @Test
    void rejectsNegativeVersion() throws Exception {
        MvcResult result = postJson("/api/admin/articles/" + ARTICLE_ID + "/offline", "{\"version\": -1}");

        assertError(result, 400, "文章版本不能为负数");
        assertThat(stubService.singleCalls).isEmpty();
    }

    @Test
    void rejectsEmptyBatch() throws Exception {
        MvcResult result = postJson("/api/admin/articles/batch/publish", "{\"items\": []}");

        assertError(result, 400, "文章列表不能为空");
        assertThat(stubService.batchCalls).isEmpty();
    }

    @Test
    void rejectsBatchLargerThanOneHundred() throws Exception {
        String items = IntStream.rangeClosed(1, 101)
                .mapToObj(index -> itemJson(String.valueOf(index), 0))
                .collect(Collectors.joining(","));

        MvcResult result = postJson("/api/admin/articles/batch/offline", "{\"items\": [" + items + "]}");

        assertError(result, 400, "批量数量不能超过 100");
        assertThat(stubService.batchCalls).isEmpty();
    }

    @Test
    void rejectsNonCanonicalItemId() throws Exception {
        MvcResult result = postJson("/api/admin/articles/batch/publish",
                batchBody(itemJson("001", 0)));

        assertError(result, 400, "文章 ID 必须是十进制正整数");
        assertThat(stubService.batchCalls).isEmpty();
    }

    @Test
    void rejectsNonNumericPathId() throws Exception {
        MvcResult result = postJson("/api/admin/articles/abc/publish", "{\"version\": 1}");

        assertError(result, 400, "请求参数错误");
        assertThat(stubService.singleCalls).isEmpty();
    }

    @Test
    void rejectsLeadingZeroPathId() throws Exception {
        MvcResult result = postJson("/api/admin/articles/001/offline", "{\"version\": 1}");

        assertError(result, 400, "请求参数错误");
        assertThat(stubService.singleCalls).isEmpty();
    }

    @Test
    void rejectsPathIdExceedingLongMaxValue() throws Exception {
        MvcResult result = postJson("/api/admin/articles/9223372036854775808/publish", "{\"version\": 1}");

        assertError(result, 400, "请求参数错误");
        assertThat(stubService.singleCalls).isEmpty();
    }

    @Test
    void acceptsLongMaxValuePathId() throws Exception {
        stubService.singleResult = new ArticleStateVO(Long.MAX_VALUE, ArticleStatus.PUBLISHED, 1, null);

        MvcResult result = postJson("/api/admin/articles/9223372036854775807/publish", "{\"version\": 0}");

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(stubService.singleCalls).containsExactly("publish:9223372036854775807:0");
    }

    private String batchBody(String... items) {
        return "{\"items\": [" + String.join(",", items) + "]}";
    }

    private static String itemJson(String id, Integer version) {
        if (version == null) {
            return "{\"id\": \"" + id + "\"}";
        }
        return "{\"id\": \"" + id + "\", \"version\": " + version + "}";
    }

    private MvcResult postJson(String path, String json) throws Exception {
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
    private static final class StubPublishCommandService implements PublishCommandService {

        private ArticleStateVO singleResult = new ArticleStateVO(ARTICLE_ID, ArticleStatus.PUBLISHED, 1, null);
        private List<ArticleStateVO> batchResult = List.of();
        private final List<String> singleCalls = new ArrayList<>();
        private final List<String> batchCalls = new ArrayList<>();

        @Override
        public ArticleStateVO publish(long articleId, ArticleVersionRequest request) {
            singleCalls.add("publish:" + articleId + ":" + request.version());
            return singleResult;
        }

        @Override
        public ArticleStateVO offline(long articleId, ArticleVersionRequest request) {
            singleCalls.add("offline:" + articleId + ":" + request.version());
            return singleResult;
        }

        @Override
        public List<ArticleStateVO> publishBatch(BatchArticlesRequest request) {
            batchCalls.add("publishBatch:" + request.items().size());
            return batchResult;
        }

        @Override
        public List<ArticleStateVO> offlineBatch(BatchArticlesRequest request) {
            batchCalls.add("offlineBatch:" + request.items().size());
            return batchResult;
        }
    }
}
