package com.xinghe.cms.permission.controller;

import com.xinghe.cms.common.request.Ids;
import com.xinghe.cms.common.request.PageQuery;
import com.xinghe.cms.common.request.QueryParams;
import com.xinghe.cms.common.response.ApiResponse;
import com.xinghe.cms.common.response.PageResult;
import com.xinghe.cms.permission.request.RoleCreateRequest;
import com.xinghe.cms.permission.request.RolePermissionsUpdateRequest;
import com.xinghe.cms.permission.request.RoleStatusRequest;
import com.xinghe.cms.permission.request.RoleUpdateRequest;
import com.xinghe.cms.permission.response.PermissionView;
import com.xinghe.cms.permission.response.RoleView;
import com.xinghe.cms.permission.service.RoleAdminService;
import com.xinghe.cms.permission.service.RolePermissionService;
import com.xinghe.cms.permission.service.RoleQueryService;
import com.xinghe.cms.security.annotation.RequirePermission;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 角色管理接口。已覆盖 OpenAPI 全部 7 个角色管理端点：分页/详情查询（D5）+
 * 创建（role-admin 第一批）+ 修改/启停（role-write 第二批）+ 已分配权限查询/
 * 全量替换（role-permission 第三批）。
 *
 * <p>鉴权：按 OpenAPI {@code x-permission}，分页需要 {@code role:list}，
 * 详情需要 {@code role:read}，创建需要 {@code role:create}，修改需要
 * {@code role:update}，启停需要 {@code role:status}，已分配权限查询需要
 * {@code role:read-permission}，全量替换需要 {@code role:assign-permission}，
 * 由 {@code RequirePermissionAspect} 统一校验。
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

    /** 详情/创建接口不接受任何查询参数。 */
    private static final Set<String> NO_QUERY_PARAMS = Set.of();

    /** status 的合法原始取值（0 停用、1 启用）。 */
    private static final Set<String> STATUS_VALUES = Set.of("0", "1");

    private final RoleQueryService roleQueryService;

    private final RoleAdminService roleAdminService;

    private final RolePermissionService rolePermissionService;

    public RoleController(RoleQueryService roleQueryService, RoleAdminService roleAdminService,
            RolePermissionService rolePermissionService) {
        this.roleQueryService = roleQueryService;
        this.roleAdminService = roleAdminService;
        this.rolePermissionService = rolePermissionService;
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

    /**
     * 创建角色（OpenAPI {@code POST /api/admin/roles}，权限 {@code role:create}）。
     * 请求体为 JSON（{@code roleCode}/{@code roleName}）：必填、非空白、64/100
     * 字符上限（码点计数，服务层校验）；未知字段由 Jackson 全局严格模式拦截 400；
     * 重复编码 409。新角色默认启用且未分配权限，返回完整 Role。
     *
     * <p>审计：由 D 的 {@code @AuditLog} 标注在事务边界内的服务方法上
     * （{@code AuditLogAspect} 要求注解不标在 Controller——事务尚未开启会使
     * success 审计在业务提交后才单独写入，写入失败接口仍 200）；操作编码取
     * OpenAPI {@code x-audit-operation}。</p>
     *
     * <p>不接受任何查询参数（契约：写操作参数在 JSON 体中）。</p>
     */
    @PostMapping
    @RequirePermission("role:create")
    public ApiResponse<RoleView> create(@Valid @RequestBody RoleCreateRequest request,
            HttpServletRequest httpRequest) {
        QueryParams.requireAllowedKeys(httpRequest.getParameterMap(), NO_QUERY_PARAMS);
        return ApiResponse.success(roleAdminService.create(request));
    }

    /**
     * 修改角色（OpenAPI {@code PUT /api/admin/roles/{id}}，权限 {@code role:update}）。
     * 契约：只改 roleName，不改变编码、状态与关联；不存在 404；返回完整 Role。
     * 请求体未知字段由 Jackson 全局严格模式拦截 400。
     *
     * <p>审计：由 D 的 {@code @AuditLog} 标注在事务边界内的服务方法上，success
     * 审计随业务事务一起提交（评审 P1）；操作编码取 OpenAPI
     * {@code x-audit-operation}。</p>
     *
     * <p>不接受任何查询参数（契约：写操作参数在 JSON 体中）。</p>
     */
    @PutMapping("/{id}")
    @RequirePermission("role:update")
    public ApiResponse<RoleView> update(@PathVariable String id,
            @Valid @RequestBody RoleUpdateRequest request,
            HttpServletRequest httpRequest) {
        QueryParams.requireAllowedKeys(httpRequest.getParameterMap(), NO_QUERY_PARAMS);
        return ApiResponse.success(roleAdminService.update(Ids.parse(id), request));
    }

    /**
     * 启停角色（OpenAPI {@code PUT /api/admin/roles/{id}/status}，权限 {@code role:status}）。
     * 契约：只改状态，不删除 user-role / role-permission 历史关联；重复设置
     * 同一状态幂等返回 200（data=null）；不存在 404。
     *
     * <p>审计：{@code @AuditLog} 标注在 {@code RoleWriteAuditMarker#roleStatusChanged}
     * 上，服务层仅在条件更新命中（状态确实变更）后调用它——重复同状态、并发
     * 未命中的幂等 200 路径不产生审计记录，审计失败也不会拖垮这些请求（评审
     * P2）；实际变更路径的 success 审计随启停事务一起提交。</p>
     *
     * <p>不接受任何查询参数（契约：写操作参数在 JSON 体中）。</p>
     */
    @PutMapping("/{id}/status")
    @RequirePermission("role:status")
    public ApiResponse<Void> setStatus(@PathVariable String id,
            @Valid @RequestBody RoleStatusRequest request,
            HttpServletRequest httpRequest) {
        QueryParams.requireAllowedKeys(httpRequest.getParameterMap(), NO_QUERY_PARAMS);
        roleAdminService.setStatus(Ids.parse(id), request);
        return ApiResponse.success();
    }

    /**
     * 角色已分配权限回显（OpenAPI {@code GET /api/admin/roles/{id}/permissions}，
     * 权限 {@code role:read-permission}）。契约：角色不存在 404；返回全部已分配
     * 权限（含停用项及真实 status）按 permissionId 升序；未分配为 {@code []}。
     * 该列表仅用于管理界面回显，鉴权只认 {@code AuthorizationSnapshot}。
     *
     * <p>路径 ID 遵循契约十进制字符串规则，非法格式 400；不接受任何查询参数。</p>
     */
    @GetMapping("/{id}/permissions")
    @RequirePermission("role:read-permission")
    public ApiResponse<List<PermissionView>> permissions(@PathVariable String id,
            HttpServletRequest httpRequest) {
        QueryParams.requireAllowedKeys(httpRequest.getParameterMap(), NO_QUERY_PARAMS);
        return ApiResponse.success(rolePermissionService.getPermissions(Ids.parse(id)));
    }

    /**
     * 全量替换角色权限（OpenAPI {@code PUT /api/admin/roles/{id}/permissions}，
     * 权限 {@code role:assign-permission}）。契约：空数组清空；不存在/停用权限
     * 分别 404/409；单事务校验并替换、失败回滚；相同集合幂等 200（data=null）。
     * 请求体 {@code RolePermissionsUpdate} 仅 {@code permissionIds}（IdList：
     * 0～100 个十进制正整数字符串、去重），由 record 级严格反序列化器在绑定
     * 阶段校验，未知字段/类型错误/重复/越界一律 400。
     *
     * <p>审计：{@code @AuditLog(operation="role.assign-permission")} 标注在事务
     * 边界内的 {@code RoleWriteAuditMarker#rolePermissionsChanged} 锚点上，仅在
     * 权限集合实际变化时记录；幂等（相同集合）路径不写库、不产生审计记录。
     *
     * <p>不接受任何查询参数（契约：写操作参数在 JSON 体中）。</p>
     */
    @PutMapping("/{id}/permissions")
    @RequirePermission("role:assign-permission")
    public ApiResponse<Void> setPermissions(@PathVariable String id,
            @Valid @RequestBody RolePermissionsUpdateRequest request,
            HttpServletRequest httpRequest) {
        QueryParams.requireAllowedKeys(httpRequest.getParameterMap(), NO_QUERY_PARAMS);
        rolePermissionService.replacePermissions(Ids.parse(id), request);
        return ApiResponse.success();
    }
}
