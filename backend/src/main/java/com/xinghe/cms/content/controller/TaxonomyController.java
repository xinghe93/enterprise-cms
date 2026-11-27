package com.xinghe.cms.content.controller;

import com.xinghe.cms.common.request.PageQuery;
import com.xinghe.cms.common.request.QueryParams;
import com.xinghe.cms.common.response.ApiResponse;
import com.xinghe.cms.common.response.PageResult;
import com.xinghe.cms.content.service.CategoryService;
import com.xinghe.cms.content.service.PortalContentCache;
import com.xinghe.cms.content.service.TagService;
import com.xinghe.cms.content.vo.CategoryVO;
import com.xinghe.cms.content.vo.TagVO;
import com.xinghe.cms.security.annotation.RequirePermission;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class TaxonomyController {

    private static final Set<String> CATEGORY_PARAMS = Set.of("status");
    private static final Set<String> ADMIN_TAG_PARAMS = Set.of("current", "size", "keyword", "status");
    private static final Set<String> PORTAL_TAG_PARAMS = Set.of("current", "size", "keyword");
    private static final Set<String> STATUS_VALUES = Set.of("0", "1");

    private final CategoryService categoryService;
    private final TagService tagService;
    private final PortalContentCache portalCache;

    public TaxonomyController(CategoryService categoryService, TagService tagService, PortalContentCache portalCache) {
        this.categoryService = categoryService;
        this.tagService = tagService;
        this.portalCache = portalCache;
    }

    @GetMapping("/admin/categories")
    @RequirePermission("category:tree")
    public ApiResponse<List<CategoryVO>> adminCategories(HttpServletRequest request) {
        Map<String, String[]> params = checkedParams(request, CATEGORY_PARAMS);
        return ApiResponse.success(categoryService.adminTree(status(params)));
    }

    @GetMapping("/portal/categories")
    @RequirePermission("portal:category:tree")
    public ApiResponse<List<CategoryVO>> portalCategories(HttpServletRequest request) {
        checkedParams(request, Set.of());
        return ApiResponse.success(portalCache.categoryTree(categoryService::portalTree));
    }

    @GetMapping("/admin/tags")
    @RequirePermission("tag:list")
    public ApiResponse<PageResult<TagVO>> adminTags(HttpServletRequest request) {
        Map<String, String[]> params = checkedParams(request, ADMIN_TAG_PARAMS);
        return ApiResponse.success(tagService.adminPage(pageQuery(params), value(params, "keyword"), status(params)));
    }

    @GetMapping("/portal/tags")
    @RequirePermission("portal:tag:list")
    public ApiResponse<PageResult<TagVO>> portalTags(HttpServletRequest request) {
        Map<String, String[]> params = checkedParams(request, PORTAL_TAG_PARAMS);
        return ApiResponse.success(tagService.portalPage(pageQuery(params), value(params, "keyword")));
    }

    private static Map<String, String[]> checkedParams(HttpServletRequest request, Set<String> allowedKeys) {
        Map<String, String[]> params = request.getParameterMap();
        QueryParams.requireAllowedKeys(params, allowedKeys);
        QueryParams.requireSingleValueForEachPresentKey(params);
        return params;
    }

    private static PageQuery pageQuery(Map<String, String[]> params) {
        QueryParams.requireStrictDecimalValue(params, "current");
        QueryParams.requireStrictDecimalValue(params, "size");
        PageQuery query = new PageQuery();
        if (params.containsKey("current")) {
            query.setCurrent(QueryParams.parseStrictDecimal(params, "current"));
        }
        if (params.containsKey("size")) {
            query.setSize(QueryParams.parseStrictDecimal(params, "size"));
        }
        return query;
    }

    private static Integer status(Map<String, String[]> params) {
        QueryParams.requireStrictEnumValue(params, "status", STATUS_VALUES);
        String status = value(params, "status");
        return status == null ? null : Integer.valueOf(status);
    }

    private static String value(Map<String, String[]> params, String key) {
        String[] values = params.get(key);
        return values == null ? null : values[0];
    }
}
