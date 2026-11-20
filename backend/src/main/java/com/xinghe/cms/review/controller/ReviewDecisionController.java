package com.xinghe.cms.review.controller;

import com.xinghe.cms.common.exception.BusinessException;
import com.xinghe.cms.common.request.QueryParams;
import com.xinghe.cms.common.response.ApiResponse;
import com.xinghe.cms.common.response.ResultCode;
import com.xinghe.cms.review.dto.ReviewDecisionRequest;
import com.xinghe.cms.review.service.ReviewDecisionService;
import com.xinghe.cms.review.vo.ArticleStateVO;
import com.xinghe.cms.security.annotation.RequirePermission;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * 审核裁决入口（OpenAPI /api/admin/articles/{id}/approve、/reject，D 负责）。
 *
 * <p>路由层权限由 B 的 {@link RequirePermission} 在方法执行前校验（未登录 401、缺权限 403）；
 * 数据层权限、自审拦截与状态/版本校验仍由 C 的 {@code ArticleWorkflowService.transition}
 * 在事务内再校验一次。审计日志 {@code @AuditLog} 不在此层，标在事务内的
 * {@code ReviewAuditRecorder} 上，保证成功日志随业务事务提交、流转失败也能记 FAILURE。</p>
 */
@RestController
@RequestMapping("/api/admin/articles")
public class ReviewDecisionController {

    /**
     * OpenAPI 的 Id 定义：无前导零的正十进制整数，最大值为 {@link Long#MAX_VALUE}。
     * 前导零（如 {@code 001}）、{@code 0}、十六进制与任何非数字字符都按 400 处理。
     */
    private static final Pattern ARTICLE_ID_PATTERN = Pattern.compile("[1-9][0-9]*");

    /** 审核裁决为写接口，契约 8.1 不允许任何查询参数；未知参数必须 400 且不触达 Service。 */
    private static final Set<String> ALLOWED_QUERY_PARAMS = Set.of();

    private final ReviewDecisionService reviewDecisionService;

    public ReviewDecisionController(ReviewDecisionService reviewDecisionService) {
        this.reviewDecisionService = reviewDecisionService;
    }

    @RequirePermission("article:approve")
    @PostMapping("/{id}/approve")
    public ApiResponse<ArticleStateVO> approve(@PathVariable String id,
            @Valid @RequestBody ReviewDecisionRequest request,
            HttpServletRequest servletRequest) {
        QueryParams.requireAllowedKeys(servletRequest.getParameterMap(), ALLOWED_QUERY_PARAMS);
        return ApiResponse.success(reviewDecisionService.approve(parseArticleId(id), request));
    }

    @RequirePermission("article:reject")
    @PostMapping("/{id}/reject")
    public ApiResponse<ArticleStateVO> reject(@PathVariable String id,
            @Valid @RequestBody ReviewDecisionRequest request,
            HttpServletRequest servletRequest) {
        QueryParams.requireAllowedKeys(servletRequest.getParameterMap(), ALLOWED_QUERY_PARAMS);
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
