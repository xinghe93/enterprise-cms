package com.xinghe.cms.common.exception;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import com.xinghe.cms.common.response.ApiResponse;
import com.xinghe.cms.common.response.ResultCode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@SpringBootTest
@Import(GlobalExceptionHandlerTests.TestConfig.class)
@ExtendWith(OutputCaptureExtension.class)
class GlobalExceptionHandlerTests {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JsonMapper jsonMapper;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    void malformedJsonUsesUnifiedBadRequestResponse() throws Exception {
        MvcResult result = mockMvc.perform(post("/__test/exceptions/body")
                .contentType(MediaType.APPLICATION_JSON).content("{invalid-json"))
                .andReturn();

        assertError(result, 400, "请求参数错误");
    }

    @ParameterizedTest
    @EnumSource(value = ResultCode.class, names = "SUCCESS", mode = EnumSource.Mode.EXCLUDE)
    void businessErrorsUseMatchingHttpStatusAndDefaultMessage(ResultCode code) throws Exception {
        MvcResult result = mockMvc.perform(get("/__test/exceptions/business/{code}", code))
                .andReturn();

        assertError(result, code.getCode(), code.getMessage());
    }

    @Test
    void businessConflictUsesPublicCustomMessage() throws Exception {
        assertError(mockMvc.perform(get("/__test/exceptions/conflict")).andReturn(),
                409, "用户名已存在");
    }

    @Test
    void invalidBodyReturnsConstraintMessageWithoutRejectedValue() throws Exception {
        assertError(mockMvc.perform(post("/__test/exceptions/body")
                .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"\"}"))
                .andReturn(), 400, "标题不能为空");
    }

    @Test
    void invalidModelAttributeReturnsConstraintMessage() throws Exception {
        assertError(mockMvc.perform(get("/__test/exceptions/model").param("title", ""))
                .andReturn(), 400, "标题不能为空");
    }

    @Test
    void invalidModelAttributeTypeDoesNotExposeBindingDetails() throws Exception {
        assertError(mockMvc.perform(get("/__test/exceptions/query")
                .param("current", "untrusted-input-marker")).andReturn(), 400, "请求参数错误");
    }

    @Test
    void invalidMethodParameterReturnsConstraintMessage() throws Exception {
        assertError(mockMvc.perform(get("/__test/exceptions/page").param("current", "0"))
                .andReturn(), 400, "页码必须大于零");
    }

    @Test
    void missingParameterUsesGenericBadRequestMessage() throws Exception {
        assertError(mockMvc.perform(get("/__test/exceptions/page")).andReturn(),
                400, "请求参数错误");
    }

    @Test
    void invalidParameterTypeDoesNotEchoInput() throws Exception {
        assertError(mockMvc.perform(get("/__test/exceptions/page")
                .param("current", "untrusted-input-marker")).andReturn(), 400, "请求参数错误");
    }

    @Test
    void missingBodyUsesGenericBadRequestMessage() throws Exception {
        assertError(mockMvc.perform(post("/__test/exceptions/body")
                .contentType(MediaType.APPLICATION_JSON)).andReturn(), 400, "请求参数错误");
    }

    @Test
    void missingRouteUsesUnifiedNotFoundResponse() throws Exception {
        assertError(mockMvc.perform(get("/__test/missing-route")).andReturn(),
                404, "资源不存在");
    }

    @Test
    void wrongMethodPreservesMethodNotAllowedAndAllowHeader() throws Exception {
        MvcResult result = mockMvc.perform(get("/__test/exceptions/body")).andReturn();

        assertError(result, 405, "请求方法不支持");
        assertThat(result.getResponse().getHeader(HttpHeaders.ALLOW)).contains("POST");
    }

    @Test
    void unsupportedContentTypePreservesUnsupportedMediaType() throws Exception {
        assertError(mockMvc.perform(post("/__test/exceptions/body")
                .contentType(MediaType.TEXT_PLAIN).content("input"))
                .andReturn(), 415, "请求媒体类型不支持");
    }

    @Test
    void unacceptableResponseTypePreservesNotAcceptable() throws Exception {
        assertError(mockMvc.perform(post("/__test/exceptions/body")
                .contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_XML)
                .content("{\"title\":\"正常标题\"}"))
                .andReturn(), 406, "无法生成请求所需的响应格式");
    }

    @Test
    void frameworkStatusExceptionDoesNotExposeInternalReason() throws Exception {
        assertError(mockMvc.perform(get("/__test/exceptions/status")).andReturn(),
                404, "资源不存在");
    }

    @Test
    void unexpectedExceptionIsHiddenAndLoggedWithStackTrace(CapturedOutput output) throws Exception {
        assertError(mockMvc.perform(get("/__test/exceptions/unexpected")).andReturn(),
                500, "系统繁忙，请稍后再试");

        assertThat(output.getOut()).contains("java.lang.IllegalArgumentException: internal-failure-marker")
                .contains("TestController.unexpected");
    }

    @Test
    void serverBusinessErrorDoesNotExposeCustomMessage() throws Exception {
        assertError(mockMvc.perform(get("/__test/exceptions/server-business")).andReturn(),
                500, "系统繁忙，请稍后再试");
    }

    @Test
    void invalidReturnValueIsServerErrorRatherThanBadRequest() throws Exception {
        assertError(mockMvc.perform(get("/__test/exceptions/invalid-return")).andReturn(),
                500, "系统繁忙，请稍后再试");
    }

    @Test
    void successfulResponseKeepsExistingContract() throws Exception {
        MvcResult result = mockMvc.perform(post("/__test/exceptions/body")
                .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"正常标题\"}"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode json = jsonMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(json.size()).isEqualTo(4);
        assertThat(json.get("code").asInt()).isEqualTo(200);
        assertThat(json.get("data").get("title").asString()).isEqualTo("正常标题");
    }

    private void assertError(MvcResult result, int status, String message) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(status);
        assertThat(result.getResponse().getContentType()).startsWith(MediaType.APPLICATION_JSON_VALUE);
        JsonNode json = jsonMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(json.size()).isEqualTo(4);
        assertThat(json.get("code").asInt()).isEqualTo(status);
        assertThat(json.get("message").asString()).isEqualTo(message);
        assertThat(json.has("data")).isTrue();
        assertThat(json.get("data").isNull()).isTrue();
        assertThat(json.get("timestamp").asString()).endsWith("Z");
        assertThat(Instant.parse(json.get("timestamp").asString())).isNotNull();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestConfig {

        @Bean
        TestController exceptionTestController() {
            return new TestController();
        }
    }

    @TestComponent
    @RestController
    static class TestController {

        @GetMapping("/__test/exceptions/business/{code}")
        void business(@PathVariable ResultCode code) {
            throw new BusinessException(code);
        }

        @GetMapping("/__test/exceptions/conflict")
        void conflict() {
            throw new BusinessException(ResultCode.CONFLICT, "用户名已存在");
        }

        @GetMapping("/__test/exceptions/server-business")
        void serverBusiness() {
            throw new BusinessException(ResultCode.INTERNAL_SERVER_ERROR, "internal-failure-marker");
        }

        @GetMapping("/__test/exceptions/page")
        ApiResponse<Integer> page(@RequestParam @Min(value = 1, message = "页码必须大于零") int current) {
            return ApiResponse.success(current);
        }

        @GetMapping("/__test/exceptions/model")
        ApiResponse<Body> model(@Valid @ModelAttribute Body body) {
            return ApiResponse.success(body);
        }

        @GetMapping("/__test/exceptions/query")
        ApiResponse<Query> query(@Valid @ModelAttribute Query query) {
            return ApiResponse.success(query);
        }

        @GetMapping("/__test/exceptions/status")
        void status() {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "internal-failure-marker");
        }

        @GetMapping("/__test/exceptions/unexpected")
        void unexpected() {
            throw new IllegalArgumentException("internal-failure-marker");
        }

        @NotNull(message = "internal-return-validation-marker")
        @GetMapping("/__test/exceptions/invalid-return")
        String invalidReturn() {
            return null;
        }

        @PostMapping("/__test/exceptions/body")
        ApiResponse<Body> body(@Valid @RequestBody Body body) {
            return ApiResponse.success(body);
        }
    }

    record Body(@NotBlank(message = "标题不能为空") String title) {
    }

    record Query(Integer current) {
    }
}
