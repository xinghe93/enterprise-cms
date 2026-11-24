package com.xinghe.cms.permission.controller;

import com.xinghe.cms.common.request.Ids;
import com.xinghe.cms.common.request.PageQuery;
import com.xinghe.cms.common.request.QueryParams;
import com.xinghe.cms.common.response.ApiResponse;
import com.xinghe.cms.common.response.PageResult;
import com.xinghe.cms.permission.request.PermissionCreateRequest;
import com.xinghe.cms.permission.request.PermissionStatusRequest;
import com.xinghe.cms.permission.request.PermissionUpdateRequest;
import com.xinghe.cms.permission.response.PermissionView;
import com.xinghe.cms.permission.service.PermissionAdminService;
import com.xinghe.cms.permission.service.PermissionQueryService;
import com.xinghe.cms.security.annotation.RequirePermission;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
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
 * 权限项管理接口（OpenAPI {@code /api/admin/permissions} 全部 4 个端点）：
 * 分页查询 + 创建 + 修改 + 启停。
 *
 * <p>鉴权：按 OpenAPI {@code x-permission}，分页需要 {@code permission:list}，
 * 创建需要 {@code permission:create}，修改需要 {@code permission:update}，
 * 启停需要 {@code permission:status}，由 {@code RequirePermissionAspect} 统一
 * 校验。API 权限项的 path/method 仅是元数据，实际鉴权以编码注解为准
 * （协作约定 3.4）。</p>
 *
 * <p>审计：操作编码取 OpenAPI {@code x-audit-operation}——
 * {@code permission.create} / {@code permission.update} 标注在服务方法上
 * （必然变更），{@code permission.status} 挂在
 * {@code PermissionWriteAuditMarker#permissionStatusChanged(Runnable)} 受审计
 * 代理上（幂等无变更路径跳过审计），见 {@code PermissionAdminService} JavaDoc。</p>
 *
 * <p>查询参数按契约 8.x 严格校验：未知参数、重复传参、非法数字/枚举取值一律 400
 * （校验数据源与 {@code RoleController} 相同，走
 * {@link HttpServletRequest#getParameterMap()}）。</p>
 */
@RestController
@RequestMapping("/api/admin/permissions")
public class PermissionController {

    /** 分页接口允许的查询参数全集（契约 8.x：之外的键一律 400）。 */
    private static final Set<String> PAGE_QUERY_PARAMS =
            Set.of("current", "size", "keyword", "status", "permissionType");

    /** 写接口不接受任何查询参数（契约：写操作参数在 JSON 体中）。 */
    private static final Set<String> NO_QUERY_PARAMS = Set.of();

    /** status 的合法原始取值（0 停用、1 启用）。 */
    private static final Set<String> STATUS_VALUES = Set.of("0", "1");

    /** permissionType 的合法原始取值（严格匹配 MENU/BUTTON/API）。 */
    private static final Set<String> PERMISSION_TYPE_VALUES = Set.of("MENU", "BUTTON", "API");

    private final PermissionQueryService permissionQueryService;

    private final PermissionAdminService permissionAdminService;

    public PermissionController(PermissionQueryService permissionQueryService,
            PermissionAdminService permissionAdminService) {
        this.permissionQueryService = permissionQueryService;
        this.permissionAdminService = permissionAdminService;
    }

    /**
     * 权限项分页：keyword 对 permissionCode/permissionName 做字面包含匹配
     * （原始长度先校验，再由服务层去空白），status/permissionType 精确筛选
     * （原始取值严格匹配，重复传参 400）。
     *
     * <p>分页值只从查询参数读取，不走 MVC 绑定（与 {@code RoleController#page}
     * 相同的理由：宽松解析与非查询参数来源污染）；current/size 未传时保留默认
     * 第 1 页、每页 10 条；数值范围（≥1、size≤100、偏移量不溢出）由
     * {@code PageQuery#toPage} 兜底。</p>
     */
    @GetMapping
    @RequirePermission("permission:list")
    public ApiResponse<PageResult<PermissionView>> page(HttpServletRequest request) {
        Map<String, String[]> allParams = request.getParameterMap();
        QueryParams.requireAllowedKeys(allParams, PAGE_QUERY_PARAMS);
        QueryParams.requireSingleValueForEachPresentKey(allParams);
        QueryParams.requireStrictDecimalValue(allParams, "current");
        QueryParams.requireStrictDecimalValue(allParams, "size");
        QueryParams.requireStrictEnumValue(allParams, "status", STATUS_VALUES);
        QueryParams.requireStrictEnumValue(allParams, "permissionType", PERMISSION_TYPE_VALUES);
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
        String permissionType = allParams.containsKey("permissionType")
                ? allParams.get("permissionType")[0]
                : null;
        return ApiResponse.success(
                permissionQueryService.page(query, keyword, status, permissionType));
    }

    /**
     * 创建权限项（OpenAPI {@code POST /api/admin/permissions}，权限
     * {@code permission:create}）。请求体 {@code PermissionCreate}：编码/名称/
     * 类型必填，path/method 可空；API 类型必须提供有效 path/method，MENU/BUTTON
     * 的 method 必须 null（服务层校验）；未知字段 400；重复编码 409；新权限项
     * 默认启用，返回数据库生成时间后的完整 Permission。
     *
     * <p>审计：{@code @AuditLog(operation="permission.create")} 标注在事务边界内
     * 的服务方法上（必然变更，整个方法体在受审计代理内），success 随业务事务
     * 提交、写入失败整体回滚并补 FAILURE。</p>
     *
     * <p>不接受任何查询参数（契约：写操作参数在 JSON 体中）。</p>
     */
    @PostMapping
    @RequirePermission("permission:create")
    public ApiResponse<PermissionView> create(@Valid @RequestBody PermissionCreateRequest request,
            HttpServletRequest httpRequest) {
        QueryParams.requireAllowedKeys(httpRequest.getParameterMap(), NO_QUERY_PARAMS);
        return ApiResponse.success(permissionAdminService.create(request));
    }

    /**
     * 修改权限项（OpenAPI {@code PUT /api/admin/permissions/{id}}，权限
     * {@code permission:update}）。契约：完整 PUT，{@code permissionName}/
     * {@code path}/{@code method} 三个键必须出现（path/method 值可 null）；
     * 编码与 {@code permissionType} 不可修改；按数据库已有类型执行创建时同一
     * 条件不变量；不存在 404；返回完整 Permission。
     *
     * <p>审计：{@code @AuditLog(operation="permission.update")} 标注在事务边界内
     * 的服务方法上（必然变更）。</p>
     *
     * <p>不接受任何查询参数（契约：写操作参数在 JSON 体中）。</p>
     */
    @PutMapping("/{id}")
    @RequirePermission("permission:update")
    public ApiResponse<PermissionView> update(@PathVariable String id,
            @Valid @RequestBody PermissionUpdateRequest request,
            HttpServletRequest httpRequest) {
        QueryParams.requireAllowedKeys(httpRequest.getParameterMap(), NO_QUERY_PARAMS);
        return ApiResponse.success(permissionAdminService.update(Ids.parse(id), request));
    }

    /**
     * 启停权限项（OpenAPI {@code PUT /api/admin/permissions/{id}/status}，权限
     * {@code permission:status}）。契约：只改状态、保留角色关联；重复设置同一
     * 状态幂等返回 200（data=null）；不存在 404。提交后有效权限立即按新状态
     * 计算——首期无跨请求权限缓存（每请求实时计算），无对象可失效。
     *
     * <p>审计：{@code @AuditLog(operation="permission.status")} 挂在
     * {@code PermissionWriteAuditMarker#permissionStatusChanged(Runnable)} 受审计
     * 代理上，实际状态更新以 Runnable 传入、在代理内部执行（写库前注册回滚
     * 回调）：幂等无变更路径不写库、不产生审计；写入失败随事务回滚并补记
     * FAILURE。</p>
     *
     * <p>不接受任何查询参数（契约：写操作参数在 JSON 体中）。</p>
     */
    @PutMapping("/{id}/status")
    @RequirePermission("permission:status")
    public ApiResponse<Void> setStatus(@PathVariable String id,
            @Valid @RequestBody PermissionStatusRequest request,
            HttpServletRequest httpRequest) {
        QueryParams.requireAllowedKeys(httpRequest.getParameterMap(), NO_QUERY_PARAMS);
        permissionAdminService.setStatus(Ids.parse(id), request);
        return ApiResponse.success();
    }
}
