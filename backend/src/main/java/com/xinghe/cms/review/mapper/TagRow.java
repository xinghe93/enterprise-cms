package com.xinghe.cms.review.mapper;

import java.time.LocalDateTime;

/**
 * 标签共享锁校验的只读行（对应 cms_tag 表）。
 */
public record TagRow(Long id, String tagName, int status, LocalDateTime createdAt, LocalDateTime updatedAt) {
}
