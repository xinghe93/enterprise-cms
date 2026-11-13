package com.xinghe.cms.log.controller;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

import com.xinghe.cms.common.exception.BusinessException;
import com.xinghe.cms.common.response.PageResult;
import com.xinghe.cms.common.response.ResultCode;
import com.xinghe.cms.log.dto.OperationLogQuery;
import com.xinghe.cms.log.service.OperationLogService;
import com.xinghe.cms.log.vo.OperationLogVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * 日志查询接口的 HTTP 契约、参数校验与统一异常响应测试。
 * 用 @Primary 替身覆盖 Service，保留真实 Controller/Validation/GlobalExceptionHandler，不连接 MySQL。
 * 注：本机 JDK 25 下 Mockito 无法自附加（MockMaker 初始化失败），因此不使用 @MockitoBean。
 * Service 内部逻辑（时间区间、操作编码归一化、详情 404）由 OperationLogServiceTests 用动态代理覆盖。
 */
@SpringBootTest
@Import(OperationLogControllerTests.TestConfig.class)
class OperationLogControllerTests {

    /** 统一响应固定四个字段：code / message / data / timestamp（见 docs 统一响应格式）。 */
    private static final int UNIFIED_RESPONSE_FIELD_COUNT = 4;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private StubOperationLogService stubService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        stubService.reset();
    }

    @Test
    void pagesLogsWithStringIdsAndPageMetadata() throws Exception {
        stubService.pageResult = new PageResult<>(List.of(sampleLog(123L)), 1, 1, 10);

        MvcResult result = mockMvc.perform(get("/api/admin/logs")
                .param("current", "1").param("size", "10")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = body(result);
        assertThat(body.get("code").asInt()).isEqualTo(200);
        JsonNode data = body.get("data");
        assertThat(data.get("records").size()).isEqualTo(1);
        JsonNode record = data.get("records").get(0);
        assertThat(record.get("id").isString()).isTrue();
        assertThat(record.get("id").asString()).isEqualTo("123");
        assertThat(record.get("userId").asString()).isEqualTo("9");
        assertThat(record.get("userName").asString()).isEqualTo("审核员");
        assertThat(record.get("createdAt").asString()).isEqualTo("2026-11-02T16:00:00.120");
        assertThat(data.get("total").asLong()).isEqualTo(1);
        assertThat(data.get("current").asLong()).isEqualTo(1);
        assertThat(data.get("size").asLong()).isEqualTo(10);
    }

    @Test
    void rejectsOversizedPageSizeBeforeServiceIsCalled() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/admin/logs").param("size", "101")).andReturn();

        assertError(result, 400, "每页数量必须在 1 到 100 之间");
        assertThat(stubService.pageCalled).isFalse();
    }

    @Test
    void rejectsUnknownResultValueBeforeServiceIsCalled() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/admin/logs").param("result", "OTHER")).andReturn();

        assertError(result, 400, "结果只能是 SUCCESS 或 FAILURE");
        assertThat(stubService.pageCalled).isFalse();
    }

    @Test
    void returnsNotFoundWhenServiceReportsMissingLog() throws Exception {
        stubService.detailError = new BusinessException(ResultCode.NOT_FOUND, "日志不存在");

        MvcResult result = mockMvc.perform(get("/api/admin/logs/99")).andReturn();

        assertError(result, 404, "日志不存在");
    }

    @Test
    void returnsLogDetailWithStringId() throws Exception {
        stubService.detailResult = sampleLog(7L);

        MvcResult result = mockMvc.perform(get("/api/admin/logs/7")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = body(result);
        assertThat(body.get("code").asInt()).isEqualTo(200);
        JsonNode data = body.get("data");
        assertThat(data.get("id").isString()).isTrue();
        assertThat(data.get("id").asString()).isEqualTo("7");
        assertThat(data.get("operation").asString()).isEqualTo("article.approve");
        assertThat(data.get("durationMs").asLong()).isEqualTo(15L);
    }

    @Test
    void rejectsNonNumericDetailIdWithUnifiedBadRequest() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/admin/logs/abc")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(result.getResponse().getContentType()).startsWith(MediaType.APPLICATION_JSON_VALUE);
        assertUnifiedErrorBody(body(result), 400);
    }

    @Test
    void rejectsUnknownQueryParameterBeforeServiceIsCalled() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/admin/logs").param("foo", "bar")).andReturn();

        assertError(result, 400, "未知的查询参数：foo");
        assertThat(stubService.pageCalled).isFalse();
    }

    @Test
    void rejectsUnknownQueryParametersOnDetailEndpoint() throws Exception {
        stubService.detailResult = sampleLog(7L);

        MvcResult result = mockMvc.perform(get("/api/admin/logs/7").param("token", "secret")).andReturn();

        assertError(result, 400, "未知的查询参数：token");
    }

    @Test
    void rejectsMultipleUnknownQueryParametersInStableOrder() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/admin/logs")
                .param("zz", "1").param("aa", "2").param("current", "1")).andReturn();

        assertError(result, 400, "未知的查询参数：aa、zz");
    }

    @Test
    void rejectsListOnlyParameterOnDetailEndpoint() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/admin/logs/7").param("size", "10")).andReturn();

        assertError(result, 400, "未知的查询参数：size");
    }

    @Test
    void rejectsOversizedOperationBeforeServiceIsCalled() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/admin/logs").param("operation", "a".repeat(101))).andReturn();

        assertError(result, 400, "操作类型长度不能超过 100");
        assertThat(stubService.pageCalled).isFalse();
    }

    @Test
    void acceptsOperationOfMaximumLength() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/admin/logs").param("operation", "a".repeat(100))).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(stubService.pageCalled).isTrue();
    }

    @Test
    void rejectsTimeWithTimezoneOffsetBeforeServiceIsCalled() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/admin/logs")
                .param("createdFrom", "2026-11-08T10:00:00Z")).andReturn();

        assertError(result, 400, "请求参数错误");
        assertThat(stubService.pageCalled).isFalse();
    }

    @Test
    void rejectsNonDecimalNumericParameter() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/admin/logs").param("userId", "0x1")).andReturn();

        assertError(result, 400, "请求参数错误");
        assertThat(stubService.pageCalled).isFalse();
    }

    @Test
    void rejectsHexadecimalDetailId() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/admin/logs/0x1")).andReturn();

        assertError(result, 400, "请求参数错误");
    }

    @Test
    void acceptsLocalTimeWithOptionalMilliseconds() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/admin/logs")
                .param("createdFrom", "2026-11-08T10:00:00.120")
                .param("createdTo", "2026-11-08T11:00:00")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(stubService.pageCalled).isTrue();
    }

    private OperationLogVO sampleLog(Long id) {
        OperationLogVO vo = new OperationLogVO();
        vo.setId(id);
        vo.setUserId(9L);
        vo.setUserName("审核员");
        vo.setOperation("article.approve");
        vo.setRequestUri("/api/admin/articles/123/approve");
        vo.setHttpMethod("POST");
        vo.setResult("SUCCESS");
        vo.setDurationMs(15L);
        vo.setCreatedAt(LocalDateTime.of(2026, 11, 2, 16, 0, 0, 120_000_000));
        return vo;
    }

    private JsonNode body(MvcResult result) throws Exception {
        return jsonMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private void assertError(MvcResult result, int status, String message) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(status);
        assertThat(result.getResponse().getContentType()).startsWith(MediaType.APPLICATION_JSON_VALUE);
        JsonNode json = body(result);
        assertUnifiedErrorBody(json, status);
        assertThat(json.get("message").asString()).isEqualTo(message);
        assertThat(json.get("timestamp").asString()).endsWith("Z");
    }

    /**
     * 统一错误响应结构：四个固定字段，data 为 null，timestamp 为 UTC。
     */
    private void assertUnifiedErrorBody(JsonNode json, int status) {
        assertThat(json.size()).isEqualTo(UNIFIED_RESPONSE_FIELD_COUNT);
        assertThat(json.get("code").asInt()).isEqualTo(status);
        assertThat(json.get("data").isNull()).isTrue();
        assertThat(json.get("message").isTextual()).isTrue();
        assertThat(json.get("timestamp").isTextual()).isTrue();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestConfig {

        @Bean
        @Primary
        StubOperationLogService stubOperationLogService() {
            return new StubOperationLogService();
        }
    }

    /**
     * 可配置的 Service 替身，用于隔离 HTTP 层与数据库/业务实现。
     */
    static class StubOperationLogService implements OperationLogService {

        private PageResult<OperationLogVO> pageResult = new PageResult<>(List.of(), 0, 1, 10);
        private OperationLogVO detailResult;
        private RuntimeException pageError;
        private RuntimeException detailError;
        private boolean pageCalled;

        @Override
        public PageResult<OperationLogVO> page(OperationLogQuery query) {
            pageCalled = true;
            if (pageError != null) {
                throw pageError;
            }
            return pageResult;
        }

        @Override
        public OperationLogVO detail(Long id) {
            if (detailError != null) {
                throw detailError;
            }
            return detailResult;
        }

        void reset() {
            pageResult = new PageResult<>(List.of(), 0, 1, 10);
            detailResult = null;
            pageError = null;
            detailError = null;
            pageCalled = false;
        }
    }
}
