package com.xinghe.cms.review.service;

import com.xinghe.cms.review.dto.ReviewDecisionRequest;
import com.xinghe.cms.review.vo.ArticleStateVO;

/**
 * 审核裁决服务：审核通过 / 驳回。
 *
 * <p>状态流转、权限校验（article:approve / article:reject）、禁止自审、版本与状态
 * 冲突检测都由 C 的 {@code ArticleWorkflowService.transition} 在同一事务内完成；
 * 本服务负责在事务内追加一条 cms_review 审核历史，并返回文章新状态。</p>
 */
public interface ReviewDecisionService {

    /** 审核通过：仅 PENDING_REVIEW → APPROVED，写审核历史并返回新状态。 */
    ArticleStateVO approve(long articleId, ReviewDecisionRequest request);

    /** 审核驳回：仅 PENDING_REVIEW → REJECTED，驳回意见必填。 */
    ArticleStateVO reject(long articleId, ReviewDecisionRequest request);
}
