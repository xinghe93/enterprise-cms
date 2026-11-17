package com.xinghe.cms.review.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 批量发布/下架请求体，对应 OpenAPI 的 BatchArticles。
 *
 * <p>items 为 1..100 项；服务端还会按文章 ID 去重，重复即 400。
 * 任一项校验或状态流转失败时整批回滚。</p>
 */
public record BatchArticlesRequest(
        @NotEmpty(message = "文章列表不能为空")
        @Size(max = 100, message = "批量数量不能超过 100")
        List<@Valid BatchArticleItemRequest> items) {
}
