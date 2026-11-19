package com.xinghe.cms.content.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.xinghe.cms.common.contract.ArticleState;
import com.xinghe.cms.common.enums.ArticleStatus;
import java.time.LocalDateTime;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record ArticleStateVO(
        String id,
        ArticleStatus status,
        int version,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS") LocalDateTime publishedAt) {

    public static ArticleStateVO from(ArticleState state) {
        return new ArticleStateVO(state.id().toString(), state.status(), state.version(), state.publishedAt());
    }
}
