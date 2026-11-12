package com.xinghe.cms.content.dto;

import com.xinghe.cms.common.enums.ArticleStatus;
import com.xinghe.cms.common.request.PageQuery;
import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ArticleQuery extends PageQuery {

    private String keyword;

    @Positive
    private Long categoryId;

    @Positive
    private Long authorId;

    @Positive
    private Long tagId;

    private ArticleStatus status;
}
