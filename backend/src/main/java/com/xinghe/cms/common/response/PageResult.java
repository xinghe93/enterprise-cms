package com.xinghe.cms.common.response;

import java.util.List;
import java.util.Objects;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * 对外分页数据，current 从 1 开始，total 表示记录总数。
 */
@JsonPropertyOrder({"records", "total", "current", "size"})
public record PageResult<T>(List<T> records, long total, long current, long size) {

    public PageResult {
        if (total < 0) {
            throw new IllegalArgumentException("记录总数不能为负数");
        }
        if (current < 1 || size < 1) {
            throw new IllegalArgumentException("页码和每页数量必须大于零");
        }
        records = List.copyOf(records);
    }

    public static <T> PageResult<T> from(IPage<T> page) {
        Objects.requireNonNull(page, "分页结果不能为空");
        return new PageResult<>(page.getRecords(), page.getTotal(), page.getCurrent(), page.getSize());
    }
}
