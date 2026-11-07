package com.xinghe.cms.log.dto;

import java.time.LocalDateTime;

import com.xinghe.cms.common.exception.BusinessException;
import com.xinghe.cms.common.response.ResultCode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 日志查询条件与分页、时间区间规则，不启动 Spring 容器。
 */
class OperationLogQueryTests {

    @Test
    void inheritsPageQueryDefaults() {
        OperationLogQuery query = new OperationLogQuery();

        assertThat(query.getCurrent()).isEqualTo(1);
        assertThat(query.getSize()).isEqualTo(10);
    }

    @Test
    void normalizesOperationByTrimmingAndTreatingBlankAsAbsent() {
        OperationLogQuery query = new OperationLogQuery();
        assertThat(query.normalizedOperation()).isNull();

        query.setOperation("   ");
        assertThat(query.normalizedOperation()).isNull();

        query.setOperation("  article.approve  ");
        assertThat(query.normalizedOperation()).isEqualTo("article.approve");
    }

    @Test
    void acceptsOpenEndedOrValidTimeRange() {
        OperationLogQuery query = new OperationLogQuery();
        query.setCreatedFrom(LocalDateTime.of(2026, 11, 1, 0, 0));
        query.setCreatedTo(LocalDateTime.of(2026, 11, 2, 0, 0));
        query.validateTimeRange();

        query.setCreatedTo(null);
        query.validateTimeRange();

        query.setCreatedFrom(null);
        query.setCreatedTo(LocalDateTime.of(2026, 11, 2, 0, 0));
        query.validateTimeRange();
    }

    @Test
    void rejectsTimeRangeWhoseStartIsNotBeforeEnd() {
        OperationLogQuery query = new OperationLogQuery();
        LocalDateTime sameTime = LocalDateTime.of(2026, 11, 2, 0, 0);
        query.setCreatedFrom(sameTime);
        query.setCreatedTo(sameTime);

        assertThatThrownBy(query::validateTimeRange)
                .isInstanceOf(BusinessException.class)
                .hasMessage("起始时间必须早于结束时间")
                .satisfies(exception -> assertThat(((BusinessException) exception).getResultCode())
                        .isEqualTo(ResultCode.BAD_REQUEST));
    }
}
