package com.xinghe.cms.content.mapper.row;

import com.xinghe.cms.common.enums.ArticleStatus;

import java.time.LocalDateTime;

/** 正文只随详情查询读取，分页投影中的 content 为 null。 */
public record ArticleRow(
        Long id, String title, String summary, Long categoryId, String categoryName,
        Long authorId, String authorName, ArticleStatus status, String visibility, int version,
        LocalDateTime publishedAt, LocalDateTime createdAt, LocalDateTime updatedAt, String content) {
}
