package com.xinghe.cms.review.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 单个文章命令（发布/下架）的请求体，对应 OpenAPI 的 ArticleVersion。
 *
 * @param version 期望的文章版本号；必填，从 0 开始，服务端与行锁内版本严格比较
 */
public record ArticleVersionRequest(
        @NotNull(message = "文章版本不能为空")
        @Min(value = 0, message = "文章版本不能为负数")
        Integer version) {
}
