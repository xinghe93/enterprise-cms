package com.xinghe.cms.review.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.xinghe.cms.common.enums.ArticleStatus;

import java.time.LocalDateTime;

/**
 * 审核裁决结果，对应 OpenAPI 的 ArticleState 模型。
 *
 * <p>ID 按团队契约以 JSON 字符串返回；publishedAt 为毫秒精度的本地时间，
 * 未发布时为 null。</p>
 */
public record ArticleStateVO(
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        Long id,

        ArticleStatus status,

        int version,

        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS")
        LocalDateTime publishedAt) {
}
