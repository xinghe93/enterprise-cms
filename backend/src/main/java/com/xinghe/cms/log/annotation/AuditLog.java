package com.xinghe.cms.log.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记需要写入操作审计日志的 HTTP 入口方法。
 * operation 取值使用 OpenAPI 的 x-audit-operation，例如 "article.approve"。
 * 只标注写操作/关键操作；只读查询不强制审计。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface AuditLog {

    /**
     * 操作编码，对应 OpenAPI 的 x-audit-operation。
     */
    String operation();
}
