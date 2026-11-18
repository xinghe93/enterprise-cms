package com.xinghe.cms.review.controller;

import com.xinghe.cms.common.request.Ids;
import com.xinghe.cms.common.request.QueryParams;
import com.xinghe.cms.common.response.ApiResponse;
import com.xinghe.cms.common.response.PageResult;
import com.xinghe.cms.review.dto.PendingReviewQuery;
import com.xinghe.cms.review.service.PendingReviewService;
import com.xinghe.cms.review.vo.ArticleSummaryVO;
import com.xinghe.cms.security.annotation.RequirePermission;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 待审文章分页查询接口（D 模块）。
 *
 * <p>鉴权：按 OpenAPI {@code x-permission}，需要 {@code article:review-list}，
 * 由 {@code RequirePermissionAspect} 统一校验（匿名/会话失效 401、普通员工 403）。</p>
 *
 * <p>查询参数按契约 8.x 严格校验：未知参数、重复传参、非法数字一律 400。
 * 数字参数直接读原始查询参数并显式构造 {@link PendingReviewQuery}，不依赖 MVC 标量绑定，
 * 避免 {@code 0x1}、{@code 1 2} 之类的取值被 Spring 宽松解析后放行。</p>
 */
@RestController
@RequestMapping("/api/admin/reviews")
@RequiredArgsConstructor
public class PendingReviewController {

    /** 契约允许的查询参数全集，之外的键一律 400。 */
    private static final Set<String> PENDING_QUERY_PARAMS =
            Set.of("current", "size", "keyword", "categoryId", "authorId");

    private final PendingReviewService pendingReviewService;

    /**
     * 分页查询待审文章；固定只返回 PENDING_REVIEW、排除审核人自己的文章，
     * 按 updated_at ASC, id ASC 排序。
     */
    @GetMapping("/pending")
    @RequirePermission("article:review-list")
    public ApiResponse<PageResult<ArticleSummaryVO>> pending(HttpServletRequest request) {
        Map<String, String[]> allParams = request.getParameterMap();
        QueryParams.requireAllowedKeys(allParams, PENDING_QUERY_PARAMS);
        QueryParams.requireSingleValueForEachPresentKey(allParams);
        QueryParams.requireStrictDecimalValue(allParams, "current");
        QueryParams.requireStrictDecimalValue(allParams, "size");
        QueryParams.requireStrictDecimalValue(allParams, "categoryId");
        QueryParams.requireStrictDecimalValue(allParams, "authorId");
        // 正则允许 19 位，但 Long 上限之外同样属于非法格式；在这里用公共 Ids 规则拦住溢出值。
        if (allParams.containsKey("categoryId")) {
            Ids.parse(allParams.get("categoryId")[0]);
        }
        if (allParams.containsKey("authorId")) {
            Ids.parse(allParams.get("authorId")[0]);
        }

        PendingReviewQuery query = new PendingReviewQuery();
        if (allParams.containsKey("current")) {
            query.setCurrent(QueryParams.parseStrictDecimal(allParams, "current"));
        }
        if (allParams.containsKey("size")) {
            query.setSize(QueryParams.parseStrictDecimal(allParams, "size"));
        }
        if (allParams.containsKey("keyword")) {
            query.setKeyword(allParams.get("keyword")[0]);
        }
        if (allParams.containsKey("categoryId")) {
            query.setCategoryId(allParams.get("categoryId")[0]);
        }
        if (allParams.containsKey("authorId")) {
            query.setAuthorId(allParams.get("authorId")[0]);
        }
        return ApiResponse.success(pendingReviewService.pending(query));
    }
}
