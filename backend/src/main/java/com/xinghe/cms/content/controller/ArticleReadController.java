package com.xinghe.cms.content.controller;

import com.xinghe.cms.common.enums.ArticleStatus;
import com.xinghe.cms.common.request.Ids;
import com.xinghe.cms.common.request.QueryParams;
import com.xinghe.cms.common.response.ApiResponse;
import com.xinghe.cms.common.response.PageResult;
import com.xinghe.cms.content.dto.ArticleQuery;
import com.xinghe.cms.content.service.ArticleReadService;
import com.xinghe.cms.content.vo.ArticleDetailVO;
import com.xinghe.cms.content.vo.ArticleSummaryVO;
import com.xinghe.cms.security.annotation.RequirePermission;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class ArticleReadController {

    private static final Set<String> ADMIN_FILTERS =
            Set.of("current", "size", "keyword", "categoryId", "authorId", "tagId", "status");
    private static final Set<String> PORTAL_FILTERS = Set.of("current", "size", "keyword", "categoryId", "tagId");
    private static final Set<String> ARTICLE_STATUSES = Arrays.stream(ArticleStatus.values())
            .map(ArticleStatus::name)
            .collect(Collectors.toUnmodifiableSet());

    private final ArticleReadService articles;

    public ArticleReadController(ArticleReadService articles) {
        this.articles = articles;
    }

    @GetMapping("/admin/articles")
    @RequirePermission("article:list")
    public ApiResponse<PageResult<ArticleSummaryVO>> adminPage(HttpServletRequest request) {
        Map<String, String[]> params = checkedParams(request, ADMIN_FILTERS);
        ArticleQuery query = pageQuery(params);
        QueryParams.requireStrictEnumValue(params, "status", ARTICLE_STATUSES);
        String status = value(params, "status");
        if (status != null) {
            query.setStatus(ArticleStatus.valueOf(status));
        }
        query.setAuthorId(optionalId(params, "authorId"));
        return ApiResponse.success(articles.adminPage(query));
    }

    @GetMapping("/admin/articles/{id}")
    @RequirePermission("article:read")
    public ApiResponse<ArticleDetailVO> adminDetail(@PathVariable String id, HttpServletRequest request) {
        QueryParams.requireAllowedKeys(request.getParameterMap(), Set.of());
        return ApiResponse.success(articles.adminDetail(Ids.parse(id)));
    }

    @GetMapping("/portal/articles")
    @RequirePermission("portal:article:list")
    public ApiResponse<PageResult<ArticleSummaryVO>> portalPage(HttpServletRequest request) {
        return ApiResponse.success(articles.portalPage(pageQuery(checkedParams(request, PORTAL_FILTERS))));
    }

    @GetMapping("/portal/articles/{id}")
    @RequirePermission("portal:article:read")
    public ApiResponse<ArticleDetailVO> portalDetail(@PathVariable String id, HttpServletRequest request) {
        QueryParams.requireAllowedKeys(request.getParameterMap(), Set.of());
        return ApiResponse.success(articles.portalDetail(Ids.parse(id)));
    }

    private static Map<String, String[]> checkedParams(HttpServletRequest request, Set<String> allowed) {
        Map<String, String[]> params = request.getParameterMap();
        QueryParams.requireAllowedKeys(params, allowed);
        QueryParams.requireSingleValueForEachPresentKey(params);
        return params;
    }

    private static ArticleQuery pageQuery(Map<String, String[]> params) {
        QueryParams.requireStrictDecimalValue(params, "current");
        QueryParams.requireStrictDecimalValue(params, "size");

        ArticleQuery query = new ArticleQuery();
        if (params.containsKey("current")) {
            query.setCurrent(QueryParams.parseStrictDecimal(params, "current"));
        }
        if (params.containsKey("size")) {
            query.setSize(QueryParams.parseStrictDecimal(params, "size"));
        }
        query.setKeyword(value(params, "keyword"));
        query.setCategoryId(optionalId(params, "categoryId"));
        query.setTagId(optionalId(params, "tagId"));
        return query;
    }

    private static Long optionalId(Map<String, String[]> params, String key) {
        String raw = value(params, key);
        return raw == null ? null : Ids.parse(raw);
    }

    private static String value(Map<String, String[]> params, String key) {
        String[] values = params.get(key);
        return values == null ? null : values[0];
    }
}
