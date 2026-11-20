package com.xinghe.cms.review.service;

import com.xinghe.cms.common.request.PageQuery;
import com.xinghe.cms.common.response.PageResult;
import com.xinghe.cms.review.vo.ReviewRecordVO;

/**
 * 审核历史查询：只读，供 { GET /api/admin/articles/{id}/reviews } 的 Controller 调用。
 */
public interface ReviewQueryService {

    /**
     * 分页查询某篇文章的审核历史。
     *
     * @param articleId 文章 ID，必须为正整数
     * @param query     分页参数（current 默认 1，size 默认 10，上限 100）
     * @return 按 reviewedAt DESC、id DESC 排序的审核记录分页
     */
    PageResult<ReviewRecordVO> page(long articleId, PageQuery query);
}
