package com.xinghe.cms.permission.controller;

import com.xinghe.cms.common.request.Ids;
import com.xinghe.cms.common.request.PageQuery;
import com.xinghe.cms.common.request.QueryParams;
import com.xinghe.cms.common.response.ApiResponse;
import com.xinghe.cms.common.response.PageResult;
import com.xinghe.cms.permission.response.RoleView;
import com.xinghe.cms.permission.service.RoleQueryService;
import com.xinghe.cms.security.annotation.RequirePermission;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.Set;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 角色管理接口。当前批次仅提供只读查询（D5）；
 * 创建/修改/启停在后续分支补充。
 *
 * <p>鉴权：按 OpenAPI {@code x-permission}，分页需要 {@code role:list}，
 * 详情需要 {@code role:read}，由 {@code RequirePermissionAspect} 统一校验。
 * 注解包名遵循契约（《跨模块服务接口》第 49 行）：
 * {@code com.xinghe.cms.security.annotation.RequirePermission}。</p>
 *
 * <p>查询参数按契约 8.x 严格校验：未知参数、重复传参、非法数字/枚举取值一律 400。
 * 校验数据源为 {@link HttpServletRequest#getParameterMap()}——它按 Servlet 规范
 * 保证值为 {@code String[]}；{@code @RequestParam Map<String, String[]>} 受泛型擦除
 * 影响在运行期可能拿到单值 String，不可靠。</p>
 */
@RestController
@RequestMapping("/api/admin/roles")
public class RoleController {

    /** 分页接口允许的查询参数全集（契约 8.x：之外的键一律 400）。 */
    private static final Set<String> PAGE_QUERY_PARAMS = Set.of("current", "size", "keyword", "status");

    /** 详情接口不接受任何查询参数。 */
    private static final Set<String> NO_QUERY_PARAMS = Set.of();

    /** status 的合法原始取值（0 停用、1 启用）。 */
    private static final Set<String> STATUS_VALUES = Set.of("0", "1");

    private final RoleQueryService roleQueryService;

    public RoleController(RoleQueryService roleQueryService) {
        this.roleQueryService = roleQueryService;
    }

    /**
     * 角色分页：keyword 对 roleCode/roleName 做字面包含匹配（原始长度先校验，
     * 再由服务层去空白），status 精确筛选（原始取值严格匹配 0/1，重复传参 400）。
     *
     * <p>分页值只从查询参数读取，不走 MVC 绑定：方法执行前的 {@code PageQuery}
     * 绑定会把 {@code 1 2} 忽略空白解析为 12、把 {@code 0x1} 解析为十六进制，
     * 且默认值可能被同名请求头等非查询参数来源覆盖。这里在通过原始取值校验后
     * 显式构造 {@link PageQuery}，current/size 未传时保留默认第 1 页、每页 10 条；
     * {@code current} 按 OpenAPI 是 {@code int64}，{@code Long.MAX_VALUE} 为合法取值
     * （配合 {@code size=1} 按契约返回 200 空页），超出 {@code long} 范围的取值返回 400；
     * 数值范围（≥1、size≤100、偏移量不溢出）由 {@code PageQuery#toPage} 兜底。</p>
     */
    @GetMapping
    @RequirePermission("role:list")
    public ApiResponse<PageResult<RoleView>> page(HttpServletRequest request) {
        Map<String, String[]> allParams = request.getParameterMap();
        QueryParams.requireAllowedKeys(allParams, PAGE_QUERY_PARAMS);
        QueryParams.requireSingleValueForEachPresentKey(allParams);
        QueryParams.requireStrictDecimalValue(allParams, "current");
        QueryParams.requireStrictDecimalValue(allParams, "size");
        QueryParams.requireStrictEnumValue(allParams, "status", STATUS_VALUES);
        PageQuery query = new PageQuery();
        if (allParams.containsKey("current")) {
            query.setCurrent(QueryParams.parseStrictDecimal(allParams, "current"));
        }
        if (allParams.containsKey("size")) {
            query.setSize(QueryParams.parseStrictDecimal(allParams, "size"));
        }
        String keyword = allParams.containsKey("keyword") ? allParams.get("keyword")[0] : null;
        Integer status = allParams.containsKey("status")
                ? Integer.valueOf(allParams.get("status")[0])
                : null;
        return ApiResponse.success(roleQueryService.page(query, keyword, status));
    }

    /**
     * 角色详情。路径 ID 遵循契约的十进制字符串规则，非法格式返回 400；
     * 不接受任何查询参数。
     */
    @GetMapping("/{id}")
    @RequirePermission("role:read")
    public ApiResponse<RoleView> detail(@PathVariable String id, HttpServletRequest request) {
        QueryParams.requireAllowedKeys(request.getParameterMap(), NO_QUERY_PARAMS);
        return ApiResponse.success(roleQueryService.getById(Ids.parse(id)));
    }
}
