package com.xinghe.cms.review.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.xinghe.cms.common.enums.ArticleStatus;
import java.util.List;

/**
 * 标签绑定结果（OpenAPI {@code ArticleTags}）：ID 以 JSON 字符串返回，
 * {@code tagIds} 按标签 ID 升序返回。
 */
public record ArticleTagsVO(
        @JsonFormat(shape = JsonFormat.Shape.STRING) Long id,
        ArticleStatus status,
        long version,
        List<String> tagIds) {
}
