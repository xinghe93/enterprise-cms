package com.xinghe.cms.review.mapper;

import java.time.LocalDateTime;

/**
 * 多篇文章标签查询的行结果（cms_article_tag JOIN cms_tag）。
 */
public record ArticleTagRow(Long articleId, Long tagId, String tagName, int status,
                            LocalDateTime createdAt, LocalDateTime updatedAt) {
}
