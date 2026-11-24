package com.xinghe.cms.review.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.xinghe.cms.common.exception.BusinessException;
import com.xinghe.cms.common.request.PageQuery;
import com.xinghe.cms.common.response.PageResult;
import com.xinghe.cms.common.response.ResultCode;
import com.xinghe.cms.content.service.ArticleAccessService;
import com.xinghe.cms.review.mapper.ReviewRecordMapper;
import com.xinghe.cms.review.service.ReviewQueryService;
import com.xinghe.cms.review.vo.ReviewRecordVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 审核历史查询实现。
 *
 * <p>读取范围完全复用 C 的 {@link ArticleAccessService#requireReadable(Long)}：
 * 本人、具备审核能力者的非草稿、或管理员可读；review:list 权限不会扩大文章读取范围。</p>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReviewQueryServiceImpl implements ReviewQueryService {

    private final ReviewRecordMapper reviewRecordMapper;
    private final ArticleAccessService articleAccessService;

    @Override
    public PageResult<ReviewRecordVO> page(long articleId, PageQuery query) {
        if (articleId <= 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "文章 ID 必须为正整数");
        }
        articleAccessService.requireReadable(articleId);
        Page<ReviewRecordVO> page = query.toPage();
        reviewRecordMapper.selectReviewPage(page, articleId);
        return PageResult.from(page);
    }
}
