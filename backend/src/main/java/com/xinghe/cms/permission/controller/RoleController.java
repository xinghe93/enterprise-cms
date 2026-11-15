package com.xinghe.cms.permission.controller;

import com.xinghe.cms.common.request.Ids;
import com.xinghe.cms.common.request.PageQuery;
import com.xinghe.cms.common.request.QueryParams;
import com.xinghe.cms.common.response.ApiResponse;
import com.xinghe.cms.common.response.PageResult;
import com.xinghe.cms.permission.annotation.RequirePermission;
import com.xinghe.cms.permission.response.RoleView;
import com.xinghe.cms.permission.service.RoleQueryService;
import jakarta.validation.Valid;
import java.util.Map;
import java.util.Set;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 角色管理接口。当前批次仅提供只读查询（D5）；
 * 创建/修改/启停在后续分支补充。
 *
 * <p>鉴权：按 OpenAPI {@code x-permission}，分页需要 {@code role:list}，
 * 详情需要 {@code role:read}，由 {@code RequirePermissionAspect} 统一校验。</p>
 *
 * <p>查询参数按契约 8.x 严格校验：未知参数、空值枚举参数一律 400，
 * 不依赖 MVC 绑定静默忽略未声明参数。</p>
 */
@RestController
@RequestMapping("/api/admin/roles")
public class RoleController {

    /** 分页接口允许的查询参数全集（契约 8.x：之外的键一律 400）。 */
    private static final Set<String> PAGE_QUERY_PARAMS = Set.of("current", "size", "keyword", "status");

    /** 详情接口不接受任何查询参数。 */
    private static final Set<String> NO_QUERY_PARAMS = Set.of();

    private final RoleQueryService roleQueryService;

    public RoleController(RoleQueryService roleQueryService) {
        this.roleQueryService = roleQueryService;
    }

    /**
     * 角色分页：keyword 对 roleCode/roleName 做字面包含匹配（原始长度先校验，
     * 再由服务层去空白），status 精确筛选（出现即必须有值，且仅允许 0/1）。
     */
    @GetMapping
    @RequirePermission("role:list")
    public ApiResponse<PageResult<RoleView>> page(@Valid PageQuery query,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer status,
            @RequestParam Map<String, String> allParams) {
        QueryParams.requireAllowedKeys(allParams, PAGE_QUERY_PARAMS);
        QueryParams.requireNonBlankValueIfPresent(allParams, "status");
        return ApiResponse.success(roleQueryService.page(query, keyword, status));
    }

    /**
     * 角色详情。路径 ID 遵循契约的十进制字符串规则，非法格式返回 400；
     * 不接受任何查询参数。
     */
    @GetMapping("/{id}")
    @RequirePermission("role:read")
    public ApiResponse<RoleView> detail(@PathVariable String id,
            @RequestParam Map<String, String> allParams) {
        QueryParams.requireAllowedKeys(allParams, NO_QUERY_PARAMS);
        return ApiResponse.success(roleQueryService.getById(Ids.parse(id)));
    }
}
