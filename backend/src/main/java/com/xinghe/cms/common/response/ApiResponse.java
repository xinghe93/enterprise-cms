package com.xinghe.cms.common.response;

import java.time.Instant;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * 统一 JSON 响应体；HTTP 状态码由 Controller 或异常处理器设置。
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
@JsonPropertyOrder({"code", "message", "data", "timestamp"})
public record ApiResponse<T>(
        int code,
        String message,
        T data,
        @JsonFormat(shape = JsonFormat.Shape.STRING) Instant timestamp) {

    public ApiResponse {
        Objects.requireNonNull(message, "响应消息不能为空");
        Objects.requireNonNull(timestamp, "响应时间不能为空");
    }

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(ResultCode.SUCCESS.getCode(), ResultCode.SUCCESS.getMessage(),
                data, Instant.now());
    }

    public static ApiResponse<Void> success() {
        return success(null);
    }

    public static <T> ApiResponse<T> error(ResultCode resultCode) {
        Objects.requireNonNull(resultCode, "结果码不能为空");
        return error(resultCode, resultCode.getMessage());
    }

    public static <T> ApiResponse<T> error(ResultCode resultCode, String message) {
        Objects.requireNonNull(resultCode, "结果码不能为空");
        if (resultCode == ResultCode.SUCCESS) {
            throw new IllegalArgumentException("失败响应不能使用成功结果码");
        }
        return new ApiResponse<>(resultCode.getCode(), message, null, Instant.now());
    }
}
