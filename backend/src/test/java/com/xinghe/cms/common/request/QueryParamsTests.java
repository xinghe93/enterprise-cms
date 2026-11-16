package com.xinghe.cms.common.request;

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
}
