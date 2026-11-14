package com.xinghe.cms.review.controller;

import com.xinghe.cms.common.exception.BusinessException;
import com.xinghe.cms.common.response.ApiResponse;
import com.xinghe.cms.common.response.ResultCode;
import com.xinghe.cms.review.dto.ReviewDecisionRequest;
import com.xinghe.cms.review.service.ReviewDecisionService;
import com.xinghe.cms.review.vo.ArticleStateVO;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.regex.Pattern;

/**
 * 审核裁决入口（OpenAPI /api/admin/articles/{id}/approve、/reject，D 负责）。
 *
 * <p>功能权限（article:approve / article:reject）与自审拦截当前由 C 的
 * {@code ArticleWorkflowService.transition} 在事务内完成；等 B 交付
 * {@code @RequirePermission} 后在此补路由层权限注解（TODO）。审计日志
 * {@code @AuditLog} 等 PR #19 合入 dev 后再补（TODO）。</p>
 */
@RestController
@RequestMapping("/api/admin/articles")
public class ReviewDecisionController {

    /**
     * OpenAPI 的 Id 定义：无前导零的正十进制整数，最大值为 {@link Long#MAX_VALUE}。
     * 前导零（如 {@code 001}）、{@code 0}、十六进制与任何非数字字符都按 400 处理。
     */
    private static final Pattern ARTICLE_ID_PATTERN = Pattern.compile("[1-9][0-9]*");

    private final ReviewDecisionService reviewDecisionService;

    public ReviewDecisionController(ReviewDecisionService reviewDecisionService) {
        this.reviewDecisionService = reviewDecisionService;
    }

    // TODO B 交付 @RequirePermission 后补 article:approve
    // TODO PR #19 合入 dev 后补 @AuditLog(operation = "article.approve")
    @PostMapping("/{id}/approve")
    public ApiResponse<ArticleStateVO> approve(@PathVariable String id,
            @Valid @RequestBody ReviewDecisionRequest request) {
        return ApiResponse.success(reviewDecisionService.approve(parseArticleId(id), request));
    }

    // TODO B 交付 @RequirePermission 后补 article:reject
    // TODO PR #19 合入 dev 后补 @AuditLog(operation = "article.reject")
    @PostMapping("/{id}/reject")
    public ApiResponse<ArticleStateVO> reject(@PathVariable String id,
            @Valid @RequestBody ReviewDecisionRequest request) {
        return ApiResponse.success(reviewDecisionService.reject(parseArticleId(id), request));
    }

    private static long parseArticleId(String rawId) {
        if (rawId == null || !ARTICLE_ID_PATTERN.matcher(rawId).matches()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "请求参数错误");
        }
        try {
            return Long.parseLong(rawId);
        } catch (NumberFormatException exception) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "请求参数错误");
        }
    }
}
