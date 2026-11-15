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
        Map<String, String> params = new HashMap<>();
        params.put("current", "2");
        params.put("size", "10");
        params.put("keyword", "editor");
        params.put("status", "1");

        assertThatCode(() -> QueryParams.requireAllowedKeys(params,
                Set.of("current", "size", "keyword", "status")))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsMisspelledKey() {
        Map<String, String> params = Map.of("staus", "1");

        assertThatThrownBy(() -> QueryParams.requireAllowedKeys(params, Set.of("current", "size", "keyword", "status")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getResultCode())
                .isEqualTo(ResultCode.BAD_REQUEST);
    }

    @Test
    void allowsAbsentOptionalEnumParam() {
        assertThatCode(() -> QueryParams.requireNonBlankValueIfPresent(Map.of(), "status"))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsBlankEnumParamValue() {
        Map<String, String> params = Map.of("status", "");

        assertThatThrownBy(() -> QueryParams.requireNonBlankValueIfPresent(params, "status"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getResultCode())
                .isEqualTo(ResultCode.BAD_REQUEST);
    }
}
