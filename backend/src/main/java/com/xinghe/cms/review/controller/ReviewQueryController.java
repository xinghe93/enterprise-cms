package com.xinghe.cms.review.controller;

import com.xinghe.cms.common.exception.BusinessException;
import com.xinghe.cms.common.request.PageQuery;
import com.xinghe.cms.common.request.QueryParams;
import com.xinghe.cms.common.response.ApiResponse;
import com.xinghe.cms.common.response.PageResult;
import com.xinghe.cms.common.response.ResultCode;
import com.xinghe.cms.review.service.ReviewQueryService;
import com.xinghe.cms.review.vo.ReviewRecordVO;
import com.xinghe.cms.security.annotation.RequirePermission;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * 审核历史查询入口（OpenAPI GET /api/admin/articles/{id}/reviews，D 负责）。
 *
 * <p>读取范围复用 C 的 {@code ArticleAccessService#requireReadable}：
 * 本人、具备审核能力者的非草稿、或管理员可读；{@code review:list} 只做功能权限，
 * 不扩大文章读取范围。路由层权限由 B 的 {@link RequirePermission} 校验。</p>
 */
@RestController
@RequestMapping("/api/admin/articles")
public class ReviewQueryController {

    /** OpenAPI Id 定义：无前导零的正十进制整数，最大值为 {@link Long#MAX_VALUE}。 */
    private static final Pattern ARTICLE_ID_PATTERN = Pattern.compile("[1-9][0-9]*");

    /** 审核历史只读接口接受的查询参数；契约 8.1 下未知参数一律 400。 */
    private static final Set<String> ALLOWED_QUERY_PARAMS = Set.of("current", "size");

    private final ReviewQueryService reviewQueryService;

    public ReviewQueryController(ReviewQueryService reviewQueryService) {
        this.reviewQueryService = reviewQueryService;
    }

    @RequirePermission("review:list")
    @GetMapping("/{id}/reviews")
    public ApiResponse<PageResult<ReviewRecordVO>> reviews(@PathVariable String id,
                                                           @Valid @ModelAttribute PageQuery query,
                                                           HttpServletRequest servletRequest) {
        QueryParams.requireAllowedKeys(servletRequest.getParameterMap(), ALLOWED_QUERY_PARAMS);
        QueryParams.requireSingleValueForEachPresentKey(servletRequest.getParameterMap());
        return ApiResponse.success(reviewQueryService.page(parseArticleId(id), query));
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
