package com.xinghe.cms.common.exception;

import java.util.Objects;

import com.xinghe.cms.common.response.ResultCode;

/**
 * 可预期的业务失败；自定义消息须适合直接展示给用户。
 */
public class BusinessException extends RuntimeException {

    private final ResultCode resultCode;

    public BusinessException(ResultCode resultCode) {
        this(resultCode, Objects.requireNonNull(resultCode, "结果码不能为空").getMessage());
    }

    public BusinessException(ResultCode resultCode, String message) {
        super(Objects.requireNonNull(message, "异常消息不能为空"));
        this.resultCode = Objects.requireNonNull(resultCode, "结果码不能为空");
        if (resultCode == ResultCode.SUCCESS) {
            throw new IllegalArgumentException("业务异常不能使用成功结果码");
        }
        if (message.isBlank()) {
            throw new IllegalArgumentException("异常消息不能为空白");
        }
    }

    public ResultCode getResultCode() {
        return resultCode;
    }
}
