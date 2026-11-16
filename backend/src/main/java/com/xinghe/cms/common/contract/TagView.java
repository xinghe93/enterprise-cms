package com.xinghe.cms.common.contract;

import java.time.LocalDateTime;

public record TagView(Long id, String tagName, int status, LocalDateTime createdAt, LocalDateTime updatedAt) {
}
