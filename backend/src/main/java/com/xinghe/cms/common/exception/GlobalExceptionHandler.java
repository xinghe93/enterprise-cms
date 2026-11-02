package com.xinghe.cms.common.exception;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

import com.xinghe.cms.common.response.ApiResponse;
import com.xinghe.cms.common.response.ResultCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * 统一处理进入 Spring MVC 的异常，保留框架错误的状态码和响应头。
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(BusinessException exception) {
        ResultCode resultCode = exception.getResultCode();
        String message = exception.getMessage();
        if (resultCode.getCode() >= 500) {
            log.error("业务处理发生服务端异常", exception);
            message = ResultCode.INTERNAL_SERVER_ERROR.getMessage();
        }
        return ResponseEntity.status(resultCode.getCode()).contentType(MediaType.APPLICATION_JSON)
                .body(ApiResponse.error(resultCode, message));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpectedException(Exception exception) {
        log.error("请求处理发生未预期异常", exception);
        return ResponseEntity.internalServerError().contentType(MediaType.APPLICATION_JSON)
                .body(ApiResponse.error(ResultCode.INTERNAL_SERVER_ERROR));
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException exception,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return handleExceptionInternal(exception,
                ApiResponse.error(ResultCode.BAD_REQUEST, validationMessage(exception.getAllErrors())),
                headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException exception, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        Object body = status.is5xxServerError() ? null
                : ApiResponse.error(ResultCode.BAD_REQUEST, validationMessage(exception.getAllErrors()));
        return handleExceptionInternal(exception, body, headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception exception, Object body,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        String message = defaultMessage(status);
        if (status.is5xxServerError()) {
            log.error("请求处理发生框架服务端异常", exception);
        } else if (body instanceof ApiResponse<?> response) {
            message = response.message();
        }
        HttpHeaders responseHeaders = new HttpHeaders();
        responseHeaders.putAll(headers);
        responseHeaders.setContentType(MediaType.APPLICATION_JSON);
        ApiResponse<Void> response = new ApiResponse<>(status.value(), message, null, Instant.now());
        return super.handleExceptionInternal(exception, response, responseHeaders, status, request);
    }

    private String validationMessage(List<? extends MessageSourceResolvable> errors) {
        if (errors.stream().anyMatch(error -> error instanceof FieldError fieldError
                && fieldError.isBindingFailure())) {
            return ResultCode.BAD_REQUEST.getMessage();
        }
        String message = errors.stream()
                .map(MessageSourceResolvable::getDefaultMessage)
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .sorted()
                .collect(Collectors.joining("；"));
        return message.isEmpty() ? ResultCode.BAD_REQUEST.getMessage() : message;
    }

    private String defaultMessage(HttpStatusCode status) {
        if (status.is5xxServerError()) {
            return ResultCode.INTERNAL_SERVER_ERROR.getMessage();
        }
        return switch (status.value()) {
            case 400 -> ResultCode.BAD_REQUEST.getMessage();
            case 401 -> ResultCode.UNAUTHORIZED.getMessage();
            case 403 -> ResultCode.FORBIDDEN.getMessage();
            case 404 -> ResultCode.NOT_FOUND.getMessage();
            case 405 -> "请求方法不支持";
            case 406 -> "无法生成请求所需的响应格式";
            case 409 -> ResultCode.CONFLICT.getMessage();
            case 413 -> "上传内容超过大小限制";
            case 415 -> "请求媒体类型不支持";
            default -> "请求无法处理";
        };
    }
}
