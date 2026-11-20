package com.xinghe.cms.review.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 审核裁决请求体，对应 OpenAPI 的 ArticleReview（通过）与 ArticleReject（驳回）。
 *
 * <p>version 必须是审核人在列表/详情中读到的文章版本号，由 C 的
 * {@code ArticleWorkflowService.transition} 做乐观锁校验；comment 在驳回时必填
 * （业务层校验），通过时可为空。长度上限 1000 与 cms_review.comment 列一致。</p>
 */
public record ReviewDecisionRequest(
        @NotNull(message = "文章版本不能为空")
        @Min(value = 0, message = "文章版本不能为负数")
        Integer version,

        @Size(max = 1000, message = "审核意见不能超过 1000 字")
        String comment) {
}
