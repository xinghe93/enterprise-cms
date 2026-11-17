package com.xinghe.cms.review.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.xinghe.cms.common.enums.ArticleStatus;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

/**
 * 待审文章列表项：字段与 OpenAPI 的 {@code ArticleSummary} 一一对应。
 *
 * <p>业务 ID 按团队契约以 JSON 字符串返回；业务时间为毫秒精度本地时间；
 * tags 按标签 ID 升序，含已绑定的停用标签，无标签时为 []。</p>
 */
@Getter
@Setter
public class ArticleSummaryVO {

    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long id;

    private String title;

    private String summary;

    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long categoryId;

    private String categoryName;

    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long authorId;

    private String authorName;

    private ArticleStatus status;

    private String visibility;

    private Integer version;

    private List<ArticleTagVO> tags;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS")
    private LocalDateTime publishedAt;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS")
    private LocalDateTime createdAt;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS")
    private LocalDateTime updatedAt;
}
