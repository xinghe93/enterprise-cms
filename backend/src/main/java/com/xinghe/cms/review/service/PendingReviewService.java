package com.xinghe.cms.review.service;

import com.xinghe.cms.common.response.PageResult;
import com.xinghe.cms.review.dto.PendingReviewQuery;
import com.xinghe.cms.review.vo.ArticleSummaryVO;

/**
 * 待审文章分页查询（D 模块）。
 *
 * <p>固定只返回待审核（PENDING_REVIEW）且未删除的文章，排除审核人自己的文章；
 * 功能权限 {@code article:review-list} 由 Controller 上的
 * {@code @RequirePermission} 统一校验，本服务不做鉴权。</p>
 */
public interface PendingReviewService {

    /**
     * 分页查询待审文章。
     *
     * @param query 已通过查询参数契约校验的查询条件
     * @return 分页结果，tags 按标签 ID 升序
     */
    PageResult<ArticleSummaryVO> pending(PendingReviewQuery query);
}
