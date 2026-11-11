package com.xinghe.cms.common.contract;

import com.xinghe.cms.common.enums.ArticleStatus;

import java.time.LocalDateTime;

public record ArticleSnapshot(
        Long id, Long authorId, Long categoryId,
        ArticleStatus status, int version, LocalDateTime publishedAt) {
}
