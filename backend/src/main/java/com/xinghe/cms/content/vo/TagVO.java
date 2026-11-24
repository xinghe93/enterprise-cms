package com.xinghe.cms.content.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.xinghe.cms.common.contract.TagView;
import java.time.LocalDateTime;

public record TagVO(
        String id,
        String tagName,
        int status,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS") LocalDateTime createdAt,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS") LocalDateTime updatedAt) {

    public static TagVO from(TagView tag) {
        return new TagVO(tag.id().toString(), tag.tagName(), tag.status(), tag.createdAt(), tag.updatedAt());
    }
}
