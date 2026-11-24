package com.xinghe.cms.common.request;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.xinghe.cms.common.exception.BusinessException;
import com.xinghe.cms.common.response.ResultCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PageQueryTests {

    @Test
    void defaultsToFirstPageWithTenRecords() {
        Page<String> page = new PageQuery().toPage();

        assertThat(page.getCurrent()).isEqualTo(1);
        assertThat(page.getSize()).isEqualTo(10);
    }

    @Test
    void acceptsUpperSizeBoundaryAndCreatesIndependentPages() {
        PageQuery query = new PageQuery();
        query.setCurrent(3);
        query.setSize(100);

        Page<String> first = query.toPage();
        Page<String> second = query.toPage();

        assertThat(first.getCurrent()).isEqualTo(3);
        assertThat(first.getSize()).isEqualTo(100);
        assertThat(first).isNotSameAs(second);
    }

    @ParameterizedTest
    @CsvSource({"0,10", "-1,10", "1,0", "1,-1", "1,101", "9223372036854775807,100"})
    void rejectsInvalidAndOverflowingPagination(long current, long size) {
        PageQuery query = new PageQuery();
        query.setCurrent(current);
        query.setSize(size);

        assertThatThrownBy(query::toPage).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.getResultCode()).isEqualTo(ResultCode.BAD_REQUEST));
    }
}
