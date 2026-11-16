package com.xinghe.cms.common.request;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xinghe.cms.common.exception.BusinessException;
import com.xinghe.cms.common.response.ResultCode;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 查询参数契约校验（契约 8.x）：未知参数 400、空值枚举参数 400。 */
class QueryParamsTests {

    @Test
    void allowsAllDeclaredKeys() {
        Map<String, String[]> params = new HashMap<>();
        params.put("current", new String[] {"2"});
        params.put("size", new String[] {"10"});
        params.put("keyword", new String[] {"editor"});
        params.put("status", new String[] {"1"});

        assertThatCode(() -> QueryParams.requireAllowedKeys(params,
                Set.of("current", "size", "keyword", "status")))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsMisspelledKey() {
        Map<String, String[]> params = Map.of("staus", new String[] {"1"});

        assertThatThrownBy(() -> QueryParams.requireAllowedKeys(params, Set.of("current", "size", "keyword", "status")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getResultCode())
                .isEqualTo(ResultCode.BAD_REQUEST);
    }

    @Test
    void allowsAbsentOptionalEnumParam() {
        assertThatCode(() -> QueryParams.requireStrictEnumValue(Map.of(), "status", Set.of("0", "1")))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsBlankEnumParamValue() {
        Map<String, String[]> params = Map.of("status", new String[] {""});

        assertThatThrownBy(() -> QueryParams.requireStrictEnumValue(params, "status", Set.of("0", "1")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getResultCode())
                .isEqualTo(ResultCode.BAD_REQUEST);
    }

    /** PR #23 复审：status=0x1 会被 Spring Integer.decode 当十六进制解析，必须按原始值拒绝。 */
    @Test
    void rejectsHexVariantOfEnumValue() {
        Map<String, String[]> params = Map.of("status", new String[] {"0x1"});

        assertThatThrownBy(() -> QueryParams.requireStrictEnumValue(params, "status", Set.of("0", "1")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getResultCode())
                .isEqualTo(ResultCode.BAD_REQUEST);
    }

    /** PR #23 复审：status=1&status=2 标量绑定会静默取首值，重复传参必须 400。 */
    @Test
    void rejectsDuplicateParamValues() {
        Map<String, String[]> params = Map.of("status", new String[] {"1", "2"});

        assertThatThrownBy(() -> QueryParams.requireSingleValueForEachPresentKey(params))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getResultCode())
                .isEqualTo(ResultCode.BAD_REQUEST);
    }

    @Test
    void allowsSingleValuedParams() {
        Map<String, String[]> params = new HashMap<>();
        params.put("current", new String[] {"1"});
        params.put("status", new String[] {"0"});

        assertThatCode(() -> QueryParams.requireSingleValueForEachPresentKey(params))
                .doesNotThrowAnyException();
        assertThatCode(() -> QueryParams.requireStrictEnumValue(params, "status", Set.of("0", "1")))
                .doesNotThrowAnyException();
    }

    /** PR #23 复审 P3：current/size 未传时不校验，保留绑定默认值。 */
    @Test
    void allowsAbsentOptionalDecimalParam() {
        assertThatCode(() -> QueryParams.requireStrictDecimalValue(Map.of(), "current"))
                .doesNotThrowAnyException();
    }

    /** 复审 P2：current 为 OpenAPI int64，19 位的 int64 边界值是合法格式。 */
    @Test
    void allowsCanonicalDecimalParam() {
        Map<String, String[]> params = Map.of("current", new String[] {"1000000000000000000"},
                "size", new String[] {"999999999999999999"},
                "max", new String[] {"9223372036854775807"});

        assertThatCode(() -> {
            QueryParams.requireStrictDecimalValue(params, "current");
            QueryParams.requireStrictDecimalValue(params, "size");
            QueryParams.requireStrictDecimalValue(params, "max");
        }).doesNotThrowAnyException();
    }

    /** 复审 P2：int64 边界值配合解析，应完整还原数值而不丢失精度。 */
    @Test
    void parseStrictDecimalReturnsExactInt64BoundaryValue() {
        Map<String, String[]> params = Map.of("current", new String[] {"9223372036854775807"});

        QueryParams.requireStrictDecimalValue(params, "current");
        assertThat(QueryParams.parseStrictDecimal(params, "current")).isEqualTo(Long.MAX_VALUE);
    }

    /** 复审 P2：超出 long 范围的 19 位规范十进制在解析阶段转为 400，而不是 500。 */
    @Test
    void parseStrictDecimalRejectsValueBeyondLongMax() {
        Map<String, String[]> params = Map.of("current", new String[] {"9999999999999999999"});

        QueryParams.requireStrictDecimalValue(params, "current");
        assertThatThrownBy(() -> QueryParams.parseStrictDecimal(params, "current"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getResultCode())
                .isEqualTo(ResultCode.BAD_REQUEST);
    }

    /** PR #23 复审 P3：current=1 2 会被 Spring 数字绑定忽略空白解析为 12，必须按原始值拒绝。 */
    @Test
    void rejectsWhitespacePaddedDecimalParam() {
        Map<String, String[]> params = Map.of("current", new String[] {"1 2"});

        assertThatThrownBy(() -> QueryParams.requireStrictDecimalValue(params, "current"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getResultCode())
                .isEqualTo(ResultCode.BAD_REQUEST);
    }

    /** PR #23 复审 P3：+1/01/1e2/空串等宽松解析变体与超长取值一律 400（int64 超界由解析阶段拦截）。 */
    @Test
    void rejectsNonCanonicalDecimalVariants() {
        for (String raw : new String[] {"+1", "01", "1e2", "", "0", "-1", "1,234",
                "12345678901234567890"}) {
            Map<String, String[]> params = Map.of("size", new String[] {raw});

            assertThatThrownBy(() -> QueryParams.requireStrictDecimalValue(params, "size"))
                    .as("size=%s 应被拒绝", raw)
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getResultCode())
                    .isEqualTo(ResultCode.BAD_REQUEST);
        }
    }

    /** PR #23 复审 P3：current=1&current=2 重复传参必须 400。 */
    @Test
    void rejectsDuplicateDecimalParamValues() {
        Map<String, String[]> params = Map.of("current", new String[] {"1", "2"});

        assertThatThrownBy(() -> QueryParams.requireStrictDecimalValue(params, "current"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getResultCode())
                .isEqualTo(ResultCode.BAD_REQUEST);
    }
}
