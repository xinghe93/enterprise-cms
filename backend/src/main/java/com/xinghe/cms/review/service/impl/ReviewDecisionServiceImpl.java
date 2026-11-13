package com.xinghe.cms.review.service.impl;

import com.xinghe.cms.common.contract.ArticleState;
import com.xinghe.cms.common.contract.CurrentUser;
import com.xinghe.cms.common.enums.ArticleAction;
import com.xinghe.cms.common.exception.BusinessException;
import com.xinghe.cms.common.response.ResultCode;
import com.xinghe.cms.content.service.ArticleWorkflowService;
import com.xinghe.cms.review.dto.ReviewDecisionRequest;
import com.xinghe.cms.review.entity.ReviewRecord;
import com.xinghe.cms.review.mapper.ReviewRecordMapper;
import com.xinghe.cms.review.service.ReviewDecisionService;
import com.xinghe.cms.review.vo.ArticleStateVO;
import com.xinghe.cms.security.CurrentUserProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 审核裁决实现。
 *
 * <p>事务边界：本方法开启事务，C 的 transition 使用 MANDATORY 加入同一事务；因此
 * 文章状态更新与审核历史写入要么同时提交、要么同时回滚。审核人 ID 只取自 A 的
 * 安全入口 {@link CurrentUserProvider}，不接受请求参数。</p>
 */
@Service
@RequiredArgsConstructor
@Transactional
public class ReviewDecisionServiceImpl implements ReviewDecisionService {

    private final ReviewRecordMapper reviewRecordMapper;
    private final ArticleWorkflowService articleWorkflowService;
    private final CurrentUserProvider currentUserProvider;

    @Override
    public ArticleStateVO approve(long articleId, ReviewDecisionRequest request) {
        requireArticleId(articleId);
        int expectedVersion = requireVersion(request);
        return decide(articleId, expectedVersion, normalizeComment(request.comment()), ArticleAction.APPROVE);
    }

    @Override
    public ArticleStateVO reject(long articleId, ReviewDecisionRequest request) {
        requireArticleId(articleId);
        int expectedVersion = requireVersion(request);
        String comment = normalizeComment(request.comment());
        if (comment == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "驳回意见不能为空");
        }
        return decide(articleId, expectedVersion, comment, ArticleAction.REJECT);
    }

    private ArticleStateVO decide(long articleId, int expectedVersion, String comment, ArticleAction action) {
        // 先解析审核人，再流转状态：未登录时在产生任何写入之前直接 401，避免"状态已改、审核人取不到"的歧义。
        CurrentUser currentUser = currentUserProvider.requireCurrentUser();
        ArticleState state = articleWorkflowService.transition(articleId, expectedVersion, action);

        ReviewRecord record = new ReviewRecord();
        record.setArticleId(articleId);
        record.setReviewerId(currentUser.userId());
        record.setResult(action == ArticleAction.APPROVE
                ? ReviewRecord.RESULT_APPROVED
                : ReviewRecord.RESULT_REJECTED);
        record.setComment(comment);
        // reviewedAt 保持 null，由数据库 CURRENT_TIMESTAMP(3) 生成，Java 侧永不写入。
        reviewRecordMapper.insert(record);

        return new ArticleStateVO(state.id(), state.status(), state.version(), state.publishedAt());
    }

    private static void requireArticleId(long articleId) {
        if (articleId <= 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "文章 ID 必须为正整数");
        }
    }

    private static int requireVersion(ReviewDecisionRequest request) {
        if (request.version() == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "文章版本不能为空");
        }
        if (request.version() < 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "文章版本不能为负数");
        }
        return request.version();
    }

    private static String normalizeComment(String comment) {
        if (comment == null) {
            return null;
        }
        String trimmed = comment.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
