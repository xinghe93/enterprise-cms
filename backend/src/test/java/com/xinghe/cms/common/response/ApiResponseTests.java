package com.xinghe.cms.common.response;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class ApiResponseTests {

    @Autowired
    private JsonMapper jsonMapper;

    @Test
    void serializesSuccessWithExactlyFourFieldsAndUtcTimestamp() {
        Instant before = Instant.now();
        ApiResponse<Map<String, Object>> response = ApiResponse.success(
                Map.of("id", 7, "title", "示例文章"));
        Instant after = Instant.now();

        JsonNode json = toJson(response);

        assertThat(json.size()).isEqualTo(4);
        assertThat(json.get("code").asInt()).isEqualTo(200);
        assertThat(json.get("message").asString()).isEqualTo("操作成功");
        assertThat(json.get("data").get("id").asInt()).isEqualTo(7);
        assertThat(json.get("data").get("title").asString()).isEqualTo("示例文章");
        assertThat(json.get("timestamp").isString()).isTrue();
        assertThat(json.get("timestamp").asString()).endsWith("Z");
        assertThat(Instant.parse(json.get("timestamp").asString())).isBetween(before, after);
    }

    @Test
    void keepsNullDataInSuccessWithoutPayload() {
        JsonNode json = toJson(ApiResponse.success());

        assertThat(json.size()).isEqualTo(4);
        assertThat(json.get("code").asInt()).isEqualTo(200);
        assertThat(json.has("data")).isTrue();
        assertThat(json.get("data").isNull()).isTrue();
    }

    @Test
    void serializesAuthenticationFailureWithDefaultMessage() {
        JsonNode json = toJson(ApiResponse.error(ResultCode.UNAUTHORIZED));

        assertThat(json.size()).isEqualTo(4);
        assertThat(json.get("code").asInt()).isEqualTo(401);
        assertThat(json.get("message").asString()).isEqualTo("请先登录");
        assertThat(json.get("data").isNull()).isTrue();
        assertThat(Instant.parse(json.get("timestamp").asString())).isNotNull();
    }

    @Test
    void supportsSpecificBusinessFailureMessage() {
        JsonNode json = toJson(ApiResponse.error(ResultCode.CONFLICT, "用户名已存在"));

        assertThat(json.get("code").asInt()).isEqualTo(409);
        assertThat(json.get("message").asString()).isEqualTo("用户名已存在");
        assertThat(json.get("data").isNull()).isTrue();
    }

    @Test
    void refusesToRepresentAnErrorAsSuccess() {
        assertThatThrownBy(() -> ApiResponse.error(ResultCode.SUCCESS))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void exposesOnlyPublicPaginationFieldsFromMybatisPage() {
        Page<Map<String, Object>> source = new Page<>(3, 10, 27);
        source.setRecords(List.of(Map.of("id", 21, "title", "分页文章")));

        JsonNode json = toJson(ApiResponse.success(PageResult.from(source)));
        JsonNode data = json.get("data");

        assertThat(json.get("code").asInt()).isEqualTo(200);
        assertThat(data.size()).isEqualTo(4);
        assertThat(data.get("records").size()).isEqualTo(1);
        assertThat(data.get("records").get(0).get("id").asInt()).isEqualTo(21);
        assertThat(data.get("total").asLong()).isEqualTo(27);
        assertThat(data.get("current").asLong()).isEqualTo(3);
        assertThat(data.get("size").asLong()).isEqualTo(10);
    }

    @Test
    void representsEmptyPageAsAnEmptyArray() {
        JsonNode data = toJson(ApiResponse.success(
                PageResult.from(new Page<String>(1, 10, 0)))).get("data");

        assertThat(data.get("records").isArray()).isTrue();
        assertThat(data.get("records").size()).isZero();
        assertThat(data.get("total").asLong()).isZero();
        assertThat(data.get("current").asLong()).isEqualTo(1);
        assertThat(data.get("size").asLong()).isEqualTo(10);
    }

    @Test
    void keepsPageRecordsIndependentOfMutableSource() {
        List<String> records = new ArrayList<>(List.of("文章"));
        Page<String> source = new Page<>(1, 10, 1);
        source.setRecords(records);
        PageResult<String> result = PageResult.from(source);

        records.clear();

        assertThat(result.records()).containsExactly("文章");
        assertThatThrownBy(() -> result.records().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @ParameterizedTest
    @CsvSource({"-1, 1, 10", "0, 0, 10", "0, 1, 0", "0, -1, 10", "0, 1, -1"})
    void rejectsInvalidPaginationMetadata(long total, long current, long size) {
        assertThatThrownBy(() -> new PageResult<>(List.of(), total, current, size))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private JsonNode toJson(Object response) {
        return jsonMapper.readTree(jsonMapper.writeValueAsString(response));
    }
}
