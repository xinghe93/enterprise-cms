package com.xinghe.cms.user.controller;

import com.xinghe.cms.common.exception.BusinessException;
import com.xinghe.cms.common.response.ApiResponse;
import com.xinghe.cms.common.response.ResultCode;
import com.xinghe.cms.user.dto.DepartmentCreateRequest;
import com.xinghe.cms.user.dto.DepartmentStatusUpdateRequest;
import com.xinghe.cms.user.dto.DepartmentUpdateRequest;
import com.xinghe.cms.user.service.DepartmentAdminService;
import com.xinghe.cms.user.vo.DepartmentVO;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 部门管理入口（OpenAPI /api/admin/departments/*，A 负责）。
 *
 * <p>身份由 A 的 {@code BearerTokenAuthenticationFilter} 在 MVC 参数绑定前校验；
 * ID 格式、查询参数与请求体类型校验失败返回 400。严格 DTO 反序列化器只在 A 的
 * DTO 上生效，不改变全局 Jackson 行为。</p>
 *
 * <p><strong>权限注解待接入</strong>：B 的 {@code @RequirePermission} + AOP 在 PR #23
 * （{@code feature/role-query}）上，尚未合入 dev，当前分支不能 import 不存在的类。
 * PR #23 合入后按 OpenAPI {@code x-permission} 逐方法追加：getDepartmentTree=
 * {@code department:tree}、createDepartment={@code department:create}、
 * updateDepartment={@code department:update}、deleteDepartment={@code department:delete}、
 * setDepartmentStatus={@code department:status}，并补越权 403 用例。</p>
 *
 * <p><strong>审计注解待接入</strong>：D 的 {@code @AuditLog} 在 PR #35 待合；写操作合并后
 * 按 {@code x-audit-operation} 补注解（department.create/update/delete/status）。</p>
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
    public ApiResponse<List<DepartmentVO>> getDepartmentTree(
            @RequestParam Map<String, String> params) {
        return ApiResponse.success(departmentService.getTree(parseTreeQuery(params)));
    }

    /** 创建部门：默认启用、children=[]；parentId 省略/null 为根。 */
    @PostMapping
    public ApiResponse<DepartmentVO> createDepartment(
            @Valid @RequestBody DepartmentCreateRequest request) {
        return ApiResponse.success(departmentService.create(request));
    }

    /** 修改部门：完整 PUT，返回目标节点及实际完整子树。 */
    @PutMapping("/{id}")
    public ApiResponse<DepartmentVO> updateDepartment(@PathVariable("id") String id,
            @Valid @RequestBody DepartmentUpdateRequest request) {
        return ApiResponse.success(departmentService.update(parseId(id), request));
    }

    /** 删除空部门：存在子部门或任何员工引用返回 409。 */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> deleteDepartment(@PathVariable("id") String id) {
        departmentService.delete(parseId(id));
        return ApiResponse.success();
    }

    /** 启停部门：只改本节点，不级联；重复同状态返回 200/data=null。 */
    @PutMapping("/{id}/status")
    public ApiResponse<Void> setDepartmentStatus(@PathVariable("id") String id,
            @Valid @RequestBody DepartmentStatusUpdateRequest request) {
        departmentService.changeStatus(parseId(id), request.status());
        return ApiResponse.success();
    }

    /** 契约 8.1：未知查询参数返回 400，不能静默忽略。 */
    private Integer parseTreeQuery(Map<String, String> params) {
        for (String key : params.keySet()) {
            if (!TREE_QUERY_PARAMS.contains(key)) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "不支持的查询参数");
            }
        }
        String raw = params.get("status");
        if (raw == null) {
            return null;
        }
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
