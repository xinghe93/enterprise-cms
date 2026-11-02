package com.xinghe.cms.common.exception;

import com.xinghe.cms.common.response.ResultCode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BusinessExceptionTests {

    @Test
    void usesResultCodeAndDefaultMessage() {
        BusinessException exception = new BusinessException(ResultCode.NOT_FOUND);

        assertThat(exception.getResultCode()).isEqualTo(ResultCode.NOT_FOUND);
        assertThat(exception.getMessage()).isEqualTo("资源不存在");
        assertThat(exception).isInstanceOf(RuntimeException.class);
    }

    @Test
    void acceptsPublicBusinessMessage() {
        BusinessException exception = new BusinessException(ResultCode.CONFLICT, "用户名已存在");

        assertThat(exception.getResultCode()).isEqualTo(ResultCode.CONFLICT);
        assertThat(exception.getMessage()).isEqualTo("用户名已存在");
    }

    @Test
    void rejectsSuccessNullCodeAndBlankMessages() {
        assertThatThrownBy(() -> new BusinessException(ResultCode.SUCCESS))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BusinessException(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new BusinessException(ResultCode.CONFLICT, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new BusinessException(ResultCode.CONFLICT, "  "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
