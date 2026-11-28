package com.xinghe.cms.user.controller;

import com.xinghe.cms.common.exception.BusinessException;
import com.xinghe.cms.common.request.QueryParams;
import com.xinghe.cms.common.response.ApiResponse;
import com.xinghe.cms.common.response.ResultCode;
import com.xinghe.cms.security.annotation.RequirePermission;
import com.xinghe.cms.user.dto.DepartmentCreateRequest;
import com.xinghe.cms.user.dto.DepartmentStatusUpdateRequest;
import com.xinghe.cms.user.dto.DepartmentUpdateRequest;
import com.xinghe.cms.user.service.DepartmentAdminService;
import com.xinghe.cms.user.vo.DepartmentVO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 部门管理入口（OpenAPI /api/admin/departments/*，A 负责）。
 *
 * <p>身份由 A 的 {@code BearerTokenAuthenticationFilter} 在 MVC 参数绑定前校验；
 * ID 格式、查询参数与请求体类型校验失败返回 400。严格 DTO 反序列化器只在 A 的
 * DTO 上生效，不改变全局 Jackson 行为。</p>
 *
 * <p><strong>权限</strong>：5 个入口按 OpenAPI {@code x-permission} 标注 B 提供的
 * {@link RequirePermission}（{@code com.xinghe.cms.security.annotation}），由
 * {@code RequirePermissionAspect} 在方法执行前校验：未登录/停用 401，缺权限 403。
 * getDepartmentTree={@code department:tree}、createDepartment={@code department:create}、
 * updateDepartment={@code department:update}、deleteDepartment={@code department:delete}、
 * setDepartmentStatus={@code department:status}。</p>
 *
 * <p><strong>审计</strong>：4 个写操作的审计不标在本类——切面必须在事务开启后进入才能把
 * SUCCESS 写入业务事务；事务在 Service 的 {@code TransactionTemplate} 内开启，因此
 * {@code @AuditLog}（department.create / update / delete / status）标在事务内经代理
 * 调用的 {@code DepartmentAuditedActions} 上（PR #40 审查 P2）。只读的部门树不审计。</p>
 */
@RestController
@RequestMapping("/api/admin/departments")
public class DepartmentAdminController {

    private static final Pattern ID_PATTERN = Pattern.compile("^[1-9][0-9]{0,18}$");
    private static final Set<String> TREE_QUERY_PARAMS = Set.of("status");

    private final DepartmentAdminService departmentService;

    public DepartmentAdminController(DepartmentAdminService departmentService) {
        this.departmentService = departmentService;
    }

    /** 部门树：默认全部状态；status 筛选时由 Service 保留必要祖先的真实状态。 */
    @GetMapping
    @RequirePermission("department:tree")
    public ApiResponse<List<DepartmentVO>> getDepartmentTree(HttpServletRequest httpRequest) {
        return ApiResponse.success(departmentService.getTree(parseTreeQuery(httpRequest.getParameterMap())));
    }

    /** 创建部门：默认启用、children=[]；parentId 省略/null 为根。 */
    @PostMapping
    @RequirePermission("department:create")
    public ApiResponse<DepartmentVO> createDepartment(
            @Valid @RequestBody DepartmentCreateRequest request,
            HttpServletRequest httpRequest) {
        QueryParams.requireAllowedKeys(httpRequest.getParameterMap(), Set.of());
        return ApiResponse.success(departmentService.create(request));
    }

    /** 修改部门：完整 PUT，返回目标节点及实际完整子树。 */
    @PutMapping("/{id}")
    @RequirePermission("department:update")
    public ApiResponse<DepartmentVO> updateDepartment(@PathVariable("id") String id,
            @Valid @RequestBody DepartmentUpdateRequest request,
            HttpServletRequest httpRequest) {
        QueryParams.requireAllowedKeys(httpRequest.getParameterMap(), Set.of());
        return ApiResponse.success(departmentService.update(parseId(id), request));
    }

    /** 删除空部门：存在子部门或任何员工引用返回 409。 */
    @DeleteMapping("/{id}")
    @RequirePermission("department:delete")
    public ApiResponse<Void> deleteDepartment(@PathVariable("id") String id,
            HttpServletRequest httpRequest) {
        QueryParams.requireAllowedKeys(httpRequest.getParameterMap(), Set.of());
        departmentService.delete(parseId(id));
        return ApiResponse.success();
    }

    /** 启停部门：只改本节点，不级联；重复同状态返回 200/data=null。 */
    @PutMapping("/{id}/status")
    @RequirePermission("department:status")
    public ApiResponse<Void> setDepartmentStatus(@PathVariable("id") String id,
            @Valid @RequestBody DepartmentStatusUpdateRequest request,
            HttpServletRequest httpRequest) {
        QueryParams.requireAllowedKeys(httpRequest.getParameterMap(), Set.of());
        departmentService.changeStatus(parseId(id), request.status());
        return ApiResponse.success();
    }

    /** 契约 8.1：未知查询参数与重复查询参数均返回 400，不能静默忽略。 */
    private Integer parseTreeQuery(Map<String, String[]> params) {
        QueryParams.requireAllowedKeys(params, TREE_QUERY_PARAMS);
        QueryParams.requireSingleValueForEachPresentKey(params);
        String[] values = params.get("status");
        if (values == null) {
            return null;
        }
        String raw = values[0];
        if (!"0".equals(raw) && !"1".equals(raw)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "status 只能是 0 或 1");
        }
        return Integer.valueOf(raw);
    }

    private long parseId(String id) {
        if (id == null || !ID_PATTERN.matcher(id).matches()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "业务 ID 必须是十进制正整数字符串");
        }
        try {
            // pattern 只限制位数，19 位仍可能超过 Long.MAX_VALUE；超范围按 400 处理。
            return Long.parseLong(id);
        } catch (NumberFormatException exception) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "业务 ID 超出允许范围");
        }
    }
}
