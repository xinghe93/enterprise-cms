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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 审核历史查询入口（OpenAPI GET /api/admin/articles/{id}/reviews，D 负责）。
 *
 * <p>读取范围复用 C 的 {@code ArticleAccessService#requireReadable}：
 * 本人、具备审核能力者的非草稿、或管理员可读；{@code review:list} 只做功能权限，
 * 不扩大文章读取范围。路由层权限由 B 的 {@link RequirePermission} 校验。</p>
 *
 * <p>分页参数必须从原始查询参数读取并做严格十进制校验，不能依赖
 * {@code @ModelAttribute} 的隐式绑定：Spring MVC 会把 {@code current=0x2} 解析为第 2 页、
 * 把 {@code current=1%202} 解析为第 12 页，省略 {@code size} 时同名请求头
 * （{@code Size}）也会覆盖默认页大小，均违反契约 8.1。</p>
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
                                                           HttpServletRequest servletRequest) {
        Map<String, String[]> params = servletRequest.getParameterMap();
        QueryParams.requireAllowedKeys(params, ALLOWED_QUERY_PARAMS);
        QueryParams.requireSingleValueForEachPresentKey(params);
        QueryParams.requireStrictDecimalValue(params, "current");
        QueryParams.requireStrictDecimalValue(params, "size");
        return ApiResponse.success(reviewQueryService.page(parseArticleId(id), buildPageQuery(params)));
    }

    /**
     * 用原始查询参数构造分页对象：参数未出现时保留 {@link PageQuery} 的默认值，
     * 出现时按契约作严格十进制解析（超出 {@code long} 的取值返回 400）。
     */
    private static PageQuery buildPageQuery(Map<String, String[]> params) {
        PageQuery query = new PageQuery();
        if (params.containsKey("current")) {
            query.setCurrent(QueryParams.parseStrictDecimal(params, "current"));
        }
        if (params.containsKey("size")) {
            query.setSize(QueryParams.parseStrictDecimal(params, "size"));
        }
        // 复用 PageQuery 的契约级范围校验（页码 ≥ 1、每页 1–100、偏移量不溢出），
        // 保证即使 Service 未调用 toPage()（如测试替身）也能在任何副作用前返回 400。
        query.toPage();
        return query;
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
