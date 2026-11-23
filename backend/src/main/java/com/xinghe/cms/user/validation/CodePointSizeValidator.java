package com.xinghe.cms.user.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * {@link CodePointSize} 的实现：长度按 {@code codePointCount} 计数，与
 * AdminJson.text() 的反序列化口径一致；null 一律放行（由 @NotNull/@NotBlank 负责）。
 */
public class CodePointSizeValidator implements ConstraintValidator<CodePointSize, CharSequence> {

    private int min;
    private int max;

    @Override
    public void initialize(CodePointSize constraint) {
        min = constraint.min();
        max = constraint.max();
    }

    @Override
    public boolean isValid(CharSequence value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }
        int length = value.toString().codePointCount(0, value.length());
        return length >= min && length <= max;
    }
}
