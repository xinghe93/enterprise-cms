package com.xinghe.cms.review.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 文章摘要中的标签项：字段与 OpenAPI 的 {@code Tag} 一一对应。
 */
@Getter
@Setter
public class ArticleTagVO {

    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long id;

    private String tagName;

    private int status;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS")
    private LocalDateTime createdAt;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS")
    private LocalDateTime updatedAt;
}
