package com.xinghe.cms.review.controller;

import com.xinghe.cms.common.enums.ArticleStatus;
import com.xinghe.cms.common.exception.GlobalExceptionHandler;
import com.xinghe.cms.review.dto.ArticleTagsUpdateRequest;
import com.xinghe.cms.review.service.ArticleTagsCommandService;
import com.xinghe.cms.review.vo.ArticleTagsVO;
import com.xinghe.cms.security.annotation.RequirePermission;
import jakarta.servlet.http.HttpServletRequest;
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

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

class ArticleTagsControllerTests {

    private static final long ARTICLE_ID = 990201L;

    private final StubService stubService = new StubService();
    private MockMvc mockMvc;
    private JsonMapper jsonMapper;

    @BeforeEach
    void setUp() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.setApplicationContext(new StaticApplicationContext());
        validator.afterPropertiesSet();
        mockMvc = MockMvcBuilders
                .standaloneSetup(new ArticleTagsController(stubService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .build();
        jsonMapper = JsonMapper.builder().build();
    }

    @Test
    void bindsTagsAndReturnsContractShape() throws Exception {
        stubService.result = new ArticleTagsVO(ARTICLE_ID, ArticleStatus.REJECTED, 4, List.of("9901", "9902"));

        MvcResult result = perform("{\"version\":3,\"tagIds\":[\"9902\",\"9901\"]}");

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = body(result);
        assertThat(body.get("code").asInt()).isEqualTo(200);
        JsonNode data = body.get("data");
        assertThat(data.get("id").isString()).isTrue();
        assertThat(data.get("id").asString()).isEqualTo("990201");
        assertThat(data.get("status").asString()).isEqualTo("REJECTED");
        assertThat(data.get("version").asLong()).isEqualTo(4L);
        assertThat(data.get("tagIds").get(0).asString()).isEqualTo("9901");
        assertThat(data.get("tagIds").get(1).asString()).isEqualTo("9902");
        assertThat(stubService.capturedArticleId).isEqualTo(ARTICLE_ID);
        assertThat(stubService.capturedRequest.version()).isEqualTo(3);
        assertThat(stubService.capturedRequest.tagIds()).containsExactly("9902", "9901");
    }

    @Test
    void rejectsUnknownQueryParameterBeforeServiceIsCalled() throws Exception {
        MvcResult result = mockMvc.perform(put("/api/admin/articles/" + ARTICLE_ID + "/tags?foo=1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0,\"tagIds\":[]}"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(body(result).get("message").asString()).isEqualTo("未知查询参数: foo");
        assertThat(stubService.calls).isZero();
    }

    @Test
    void rejectsMalformedArticleId() throws Exception {
        MvcResult result = mockMvc.perform(put("/api/admin/articles/0x1/tags")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0,\"tagIds\":[]}"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(body(result).get("message").asString()).isEqualTo("请求参数错误");
        assertThat(stubService.calls).isZero();
    }

    @Test
    void rejectsMissingVersion() throws Exception {
        MvcResult result = perform("{\"tagIds\":[]}");

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(body(result).get("message").asString()).contains("文章版本不能为空");
    }

    @Test
    void rejectsNegativeVersion() throws Exception {
        MvcResult result = perform("{\"version\":-1,\"tagIds\":[]}");

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(body(result).get("message").asString()).contains("文章版本不能为负数");
    }

    @Test
    void rejectsTooManyTagIds() throws Exception {
        String tagIds = IntStream.rangeClosed(1, 101)
                .mapToObj(index -> "\"" + index + "\"")
                .collect(java.util.stream.Collectors.joining(","));
        MvcResult result = perform("{\"version\":0,\"tagIds\":[" + tagIds + "]}");

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(body(result).get("message").asString()).contains("标签数量不能超过 100");
    }

    @Test
    void rejectsFractionalVersion() throws Exception {
        MvcResult result = perform("{\"version\":-0.9,\"tagIds\":[]}");

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(body(result).get("message").asString()).isEqualTo("请求参数错误");
        assertThat(stubService.calls).isZero();
    }

    @Test
    void rejectsStringVersion() throws Exception {
        MvcResult result = perform("{\"version\":\"0\",\"tagIds\":[]}");

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(body(result).get("message").asString()).isEqualTo("请求参数错误");
        assertThat(stubService.calls).isZero();
    }

    @Test
    void rejectsNumericTagId() throws Exception {
        MvcResult result = perform("{\"version\":0,\"tagIds\":[1]}");

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(body(result).get("message").asString()).isEqualTo("请求参数错误");
        assertThat(stubService.calls).isZero();
    }

    @Test
    void declaresBindTagPermission() throws Exception {
        RequirePermission permission = ArticleTagsController.class
                .getDeclaredMethod("update", String.class, ArticleTagsUpdateRequest.class, HttpServletRequest.class)
                .getAnnotation(RequirePermission.class);
        assertThat(permission).isNotNull();
        assertThat(permission.value()).containsExactly("article:bind-tag");
    }

    private MvcResult perform(String body) throws Exception {
        return mockMvc.perform(put("/api/admin/articles/" + ARTICLE_ID + "/tags")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
    }

    private JsonNode body(MvcResult result) throws Exception {
        return jsonMapper.readTree(result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static final class StubService implements ArticleTagsCommandService {

        private ArticleTagsVO result;
        private int calls;
        private Long capturedArticleId;
        private ArticleTagsUpdateRequest capturedRequest;

        @Override
        public ArticleTagsVO update(long articleId, ArticleTagsUpdateRequest request) {
            calls++;
            capturedArticleId = articleId;
            capturedRequest = request;
            return result;
        }
    }
}
