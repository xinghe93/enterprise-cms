package com.xinghe.cms.content.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.xinghe.cms.common.contract.TagView;
import com.xinghe.cms.common.enums.ArticleStatus;
import com.xinghe.cms.content.mapper.row.ArticleRow;
import java.time.LocalDateTime;
import java.util.List;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record ArticleSummaryVO(
        String id,
        String title,
        String summary,
        String categoryId,
        String categoryName,
        String authorId,
        String authorName,
        ArticleStatus status,
        String visibility,
        int version,
        List<TagVO> tags,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS") LocalDateTime publishedAt,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS") LocalDateTime createdAt,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS") LocalDateTime updatedAt) {

    public static ArticleSummaryVO from(ArticleRow row, List<TagView> tags) {
        return new ArticleSummaryVO(row.id().toString(), row.title(), row.summary(),
                row.categoryId().toString(), row.categoryName(), row.authorId().toString(), row.authorName(),
                row.status(), row.visibility(), row.version(), tags.stream().map(TagVO::from).toList(),
                row.publishedAt(), row.createdAt(), row.updatedAt());
    }
}
