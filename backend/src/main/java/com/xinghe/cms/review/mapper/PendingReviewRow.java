package com.xinghe.cms.review.mapper;

import java.time.LocalDateTime;

/**
 * 待审文章列表的数据库行：cms_article JOIN cms_category JOIN sys_user 的投影。
 */
public record PendingReviewRow(
        Long id,
        String title,
        String summary,
        Long categoryId,
        String categoryName,
        Long authorId,
        String authorName,
        String status,
        String visibility,
        Integer version,
        LocalDateTime publishedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
