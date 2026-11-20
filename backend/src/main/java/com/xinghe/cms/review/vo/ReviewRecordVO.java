package com.xinghe.cms.review.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 审核历史列表项：字段与 OpenAPI 的 Review 模型一一对应。
 *
 * <p>ID 按团队契约以 JSON 字符串返回；reviewedAt 为毫秒精度的本地时间。</p>
 */
@Getter
@Setter
public class ReviewRecordVO {

    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long id;

    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long articleId;

    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long reviewerId;

    private String reviewerName;

    private String result;

    private String comment;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS")
    private LocalDateTime reviewedAt;
}
