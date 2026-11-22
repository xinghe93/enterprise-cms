package com.xinghe.cms.permission.validation;

import com.xinghe.cms.permission.support.ContractWhitespace;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * {@link ContractNotBlank} 的校验器：把「是否全为契约空白」委托
 * {@link ContractWhitespace#isBlank(String)}，与服务层判空/裁剪同源。
 *
 * <p>{@code null} 由 Bean Validation 约定视为不通过（与 {@code @NotBlank} 一致）；
 * 字符串是否含至少一个非 {@code \s} 码点由契约规则判定，不使用 Java
 * {@code String.isBlank()}。</p>
 */
public class ContractNotBlankValidator implements ConstraintValidator<ContractNotBlank, CharSequence> {

    @Override
    public boolean isValid(CharSequence value, ConstraintValidatorContext context) {
        if (value == null) {
            return false;
        }
        return !ContractWhitespace.isBlank(value.toString());
    }
}
