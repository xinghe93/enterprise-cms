package com.xinghe.cms.permission.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 「必填且至少含一个契约非空白字符」校验，表达 OpenAPI
 * {@code minLength: 1 + pattern: \S}。
 *
 * <p>替代 Jakarta {@code @NotBlank}：后者用 Java {@code String.isBlank()} /
 * {@code Character.isWhitespace} 判定，与契约（ECMAScript {@code \s}）不一致——
 * 会把 U+001C–U+001F 误判为空白而错拒契约允许的名称，又认不出 NBSP 家族。
 * 判定逻辑委托 {@link com.xinghe.cms.permission.support.ContractWhitespace}，
 * 与服务层去首尾空白/判空共用同一真源，避免 HTTP 入口与业务层两套规则打架
 * （评审 P2）。</p>
 *
 * <p>{@code null} 与仅含契约空白字符的输入不通过；{@code @Target} 复制
 * {@code @NotBlank} 全集，保证标注在 record component 上时的传播行为一致。</p>
 */
@Documented
@Constraint(validatedBy = ContractNotBlankValidator.class)
@Target({ElementType.METHOD, ElementType.FIELD, ElementType.ANNOTATION_TYPE,
        ElementType.CONSTRUCTOR, ElementType.PARAMETER, ElementType.TYPE_USE})
@Retention(RetentionPolicy.RUNTIME)
public @interface ContractNotBlank {

    String message() default "不能为空白";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
