package com.xinghe.cms.common.request;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.xinghe.cms.common.exception.BusinessException;
import com.xinghe.cms.common.response.ResultCode;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;

/**
 * 分页入参；只接受页码和数量，排序字段由各模块在服务端确定。
 */
@Getter
@Setter
public class PageQuery {

    public static final long MAX_SIZE = 100;

    @Min(value = 1, message = "页码必须大于零")
    private long current = 1;

    @Min(value = 1, message = "每页数量必须在 1 到 100 之间")
    @Max(value = MAX_SIZE, message = "每页数量必须在 1 到 100 之间")
    private long size = 10;

    public <T> Page<T> toPage() {
        if (current < 1) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "页码必须大于零");
        }
        if (size < 1 || size > MAX_SIZE) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "每页数量必须在 1 到 100 之间");
        }
        try {
            Math.multiplyExact(current - 1, size);
        } catch (ArithmeticException exception) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "分页偏移量超出范围");
        }
        return new Page<>(current, size);
    }
}
