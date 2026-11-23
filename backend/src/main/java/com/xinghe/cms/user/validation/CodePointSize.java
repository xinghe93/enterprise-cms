package com.xinghe.cms.user.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 按 Unicode 代码点计数的长度约束。
 *
 * <p>jakarta {@code @Size} 按 UTF-16 代码单元计数，含增补平面字符（如「𠮷」或 emoji，
 * 每字占 2 个 char）的合法值会被误判超长。员工接口的反序列化器（AdminJson.text()）
 * 已按 codePointCount 计数（PR #62），登录入口若继续用 {@code @Size}，同一账号
 * 创建可入库、登录却在验证凭据前被 400 拒绝。本注解与创建入口共用同一「字符」
 * 语义，null 由 {@code @NotNull} / {@code @NotBlank} 负责。</p>
 */
@Documented
@Constraint(validatedBy = CodePointSizeValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface CodePointSize {

    /** 最小代码点数（含）。 */
    int min() default 0;

    /** 最大代码点数（含）。 */
    int max() default Integer.MAX_VALUE;

    String message() default "长度超出允许范围";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
