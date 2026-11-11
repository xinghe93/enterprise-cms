package com.xinghe.cms.common.contract;

import com.xinghe.cms.common.enums.ArticleStatus;

import java.time.LocalDateTime;

public record ArticleState(Long id, ArticleStatus status, int version, LocalDateTime publishedAt) {
}
