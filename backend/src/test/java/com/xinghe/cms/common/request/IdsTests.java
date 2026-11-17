package com.xinghe.cms.common.request;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xinghe.cms.common.exception.BusinessException;
import com.xinghe.cms.common.response.ResultCode;
import org.junit.jupiter.api.Test;

/**
 * 路径/查询参数 ID 解析的契约回归：格式非法一律 400，
 * 含正则通过但超出 Long 范围的 19 位数值（评审 PR #23 P2 指出的 500 缺陷）。
 */
class IdsTests {

    @Test
    void parsesValidId() {
        assertThat(Ids.parse("37")).isEqualTo(37L);
        assertThat(Ids.parse("9223372036854775807")).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void rejectsNull() {
        assertThatThrownBy(() -> Ids.parse(null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getResultCode())
                .isEqualTo(ResultCode.BAD_REQUEST);
    }

    @Test
    void rejectsMalformedId() {
        for (String raw : new String[] {"", "0", "01", "-1", "abc", "12a", " 12 ", "1_2"}) {
            assertThatThrownBy(() -> Ids.parse(raw))
                    .as("非法 ID: %s", raw)
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getResultCode())
                    .isEqualTo(ResultCode.BAD_REQUEST);
        }
    }

    @Test
    void rejectsOverflowBeyondLongRange() {
        assertThatThrownBy(() -> Ids.parse("9999999999999999999"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getResultCode())
                .isEqualTo(ResultCode.BAD_REQUEST);
    }
}
