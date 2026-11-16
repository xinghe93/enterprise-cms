package com.xinghe.cms.content.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.LocalDateTime;
import java.util.List;

public record CategoryVO(
        String id,
        String parentId,
        String categoryName,
        int sortOrder,
        int status,
        List<CategoryVO> children,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS") LocalDateTime createdAt,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS") LocalDateTime updatedAt) {

    public CategoryVO {
        children = List.copyOf(children);
    }
}
