package com.xinghe.cms.review.service.impl;

import com.xinghe.cms.common.contract.ArticleState;
import com.xinghe.cms.common.exception.BusinessException;
import com.xinghe.cms.common.response.ResultCode;
import com.xinghe.cms.permission.support.ContractWhitespace;
import com.xinghe.cms.review.audit.ReviewAuditRecorder;
import com.xinghe.cms.review.dto.ReviewDecisionRequest;
import com.xinghe.cms.review.service.ReviewDecisionService;
import com.xinghe.cms.review.vo.ArticleStateVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 审核裁决实现。
 *
 * <p>本类只做参数校验与结果映射；"状态流转 + 审核记录写入"整体移入
 * {@link ReviewAuditRecorder}，由它承载 {@code @AuditLog} 审计切面。因为本方法先开启事务
 * 再调用审计 bean，成功日志会随业务事务一起提交、流转失败也会被记为 FAILURE，并且
 * 状态更新与审核历史要么同时提交、要么同时回滚。</p>
 */
@Service
@RequiredArgsConstructor
@Transactional
public class ReviewDecisionServiceImpl implements ReviewDecisionService {

    private final ReviewAuditRecorder reviewAuditRecorder;

    @Override
    public ArticleStateVO approve(long articleId, ReviewDecisionRequest request) {
        requireRequest(request);
        requireArticleId(articleId);
        int expectedVersion = requireVersion(request);
        return toVO(reviewAuditRecorder.approve(articleId, expectedVersion, normalizeComment(request.comment())));
    }

    @Override
    public ArticleStateVO reject(long articleId, ReviewDecisionRequest request) {
        requireRequest(request);
        requireArticleId(articleId);
        int expectedVersion = requireVersion(request);
        String comment = normalizeComment(request.comment());
        if (comment == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "驳回意见不能为空");
        }
        return toVO(reviewAuditRecorder.reject(articleId, expectedVersion, comment));
    }

    private static ArticleStateVO toVO(ArticleState state) {
        if (state == null) {
            // transition 约定返回非空；兜底避免 NPE 变成难以定位的 500。
            throw new IllegalStateException("文章状态更新失败");
        }
        return new ArticleStateVO(state.id(), state.status(), state.version(), state.publishedAt());
    }

    private static void requireRequest(ReviewDecisionRequest request) {
        if (request == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "请求体不能为空");
        }
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
        String trimmed = ContractWhitespace.strip(comment);
        return trimmed.isEmpty() ? null : trimmed;
    }
}
