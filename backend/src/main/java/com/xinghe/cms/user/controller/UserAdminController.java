package com.xinghe.cms.user.controller;

import com.xinghe.cms.common.exception.BusinessException;
import com.xinghe.cms.common.response.ApiResponse;
import com.xinghe.cms.common.response.ResultCode;
import com.xinghe.cms.log.annotation.AuditLog;
import com.xinghe.cms.security.annotation.RequirePermission;
import com.xinghe.cms.user.dto.UserCreateRequest;
import com.xinghe.cms.user.dto.UserListQuery;
import com.xinghe.cms.user.dto.UserRolesUpdateRequest;
import com.xinghe.cms.user.dto.UserStatusUpdateRequest;
import com.xinghe.cms.user.dto.UserUpdateRequest;
import com.xinghe.cms.user.service.UserAdminService;
import com.xinghe.cms.user.service.UserStatusService;
import com.xinghe.cms.user.vo.UserPageVO;
import com.xinghe.cms.user.vo.UserVO;
import jakarta.validation.Valid;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 员工管理入口（OpenAPI /api/admin/users/*，A 负责）。
 *
 * <p>身份由 A 的 {@code BearerTokenAuthenticationFilter} 在 MVC 参数绑定前校验，
 * 未登录/会话失效返回 401；ID 格式与请求体类型校验返回 400。</p>
 *
 * <p><strong>为什么不用 {@code @Validated} + {@code @Pattern}</strong>：类级方法验证由
 * {@code MethodValidationInterceptor} 抛出 {@code ConstraintViolationException}，公共
 * {@code GlobalExceptionHandler} 目前没有对应处理器，会被兜底成 500，违反契约的 400。
 * 在公共异常处理器补映射属于跨模块改动，需 B/C/D 一并确认；这里改由本模块在
 * {@link #parseId(String)} 内抛 {@code BusinessException(BAD_REQUEST)}，行为确定且
 * 不触碰公共文件。</p>
 *
 * <p><strong>权限</strong>：6 个入口按 OpenAPI {@code x-permission} 标注 B 提供的
 * {@link RequirePermission}（{@code com.xinghe.cms.security.annotation}），由
 * {@code RequirePermissionAspect} 在方法执行前校验有效权限：未登录/停用 401，缺权限 403。
 * listUsers={@code user:list}、createUser={@code user:create}、getUser={@code user:read}、
 * updateUser={@code user:update}、setUserStatus={@code user:status}、
 * setUserRoles={@code user:assign-role}。</p>
 *
 * <p><strong>审计</strong>：4 个写操作按 OpenAPI {@code x-audit-operation} 标注 D 提供的
 * {@link AuditLog}（user.create / user.update / user.status / user.assign-role）；
 * 只读查询不审计。</p>
 */
@RestController
@RequestMapping("/api/admin/users")
public class UserAdminController {

    /** 与 OpenAPI {@code Id} schema 一致：十进制正整数字符串，无前导零，最长 19 位。 */
    private static final Pattern ID_PATTERN = Pattern.compile("^[1-9][0-9]{0,18}$");

    /** 契约 8.1：未知查询参数返回 400，不能用宽松的 @RequestParam 悄悄忽略。 */
    private static final Set<String> USER_QUERY_PARAMS =
            Set.of("current", "size", "keyword", "departmentId", "status");

    private final UserStatusService userStatusService;
    private final UserAdminService userAdminService;

    public UserAdminController(UserStatusService userStatusService,
            UserAdminService userAdminService) {
        this.userStatusService = userStatusService;
        this.userAdminService = userAdminService;
    }

    /** 员工分页（OpenAPI listUsers）：所有可选条件 AND，省略不筛选。 */
    @GetMapping
    @RequirePermission("user:list")
    public ApiResponse<UserPageVO> listUsers(@RequestParam Map<String, String> params) {
        return ApiResponse.success(userAdminService.listUsers(parseListQuery(params)));
    }

    /** 创建员工（OpenAPI createUser）：默认启用、无角色。 */
    @PostMapping
    @RequirePermission("user:create")
    @AuditLog(operation = "user.create")
    public ApiResponse<UserVO> createUser(@Valid @RequestBody UserCreateRequest request) {
        return ApiResponse.success(userAdminService.createUser(request));
    }

    /** 员工详情（OpenAPI getUser）：目标不存在返回 404。 */
    @GetMapping("/{id}")
    @RequirePermission("user:read")
    public ApiResponse<UserVO> getUser(@PathVariable("id") String id) {
        return ApiResponse.success(userAdminService.getUser(parseId(id)));
    }

    /** 修改员工（OpenAPI updateUser）：完整 PUT，realName/departmentId 都必填。 */
    @PutMapping("/{id}")
    @RequirePermission("user:update")
    @AuditLog(operation = "user.update")
    public ApiResponse<UserVO> updateUser(@PathVariable("id") String id,
            @Valid @RequestBody UserUpdateRequest request) {
        return ApiResponse.success(userAdminService.updateUser(parseId(id), request));
    }

    /** 全量替换角色（OpenAPI setUserRoles）：成功 data=null。 */
    @PutMapping("/{id}/roles")
    @RequirePermission("user:assign-role")
    @AuditLog(operation = "user.assign-role")
    public ApiResponse<Void> setUserRoles(@PathVariable("id") String id,
            @Valid @RequestBody UserRolesUpdateRequest request) {
        userAdminService.replaceRoles(parseId(id), request);
        return ApiResponse.success();
    }

    /**
     * 启用/停用员工。成功返回 {@code data=null}；重复设置同一状态仍返回 200。
     * 会话撤销在数据库事务提交之后由 {@link UserStatusService} 完成。
     */
    @PutMapping("/{id}/status")
    @RequirePermission("user:status")
    @AuditLog(operation = "user.status")
    public ApiResponse<Void> setUserStatus(
            @PathVariable("id") String id,
            @Valid @RequestBody UserStatusUpdateRequest request) {
        userStatusService.changeStatus(parseId(id), request.status());
        return ApiResponse.success();
    }

    private UserListQuery parseListQuery(Map<String, String> params) {
        for (String key : params.keySet()) {
            if (!USER_QUERY_PARAMS.contains(key)) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "不支持的查询参数");
            }
        }
        long current = parsePositiveLong(params.get("current"), 1L, Long.MAX_VALUE);
        long size = parsePositiveLong(params.get("size"), 10L, 100L);
        try {
            // LIMIT/OFFSET 不能溢出 Long；越界按 400 而不是让 SQL 报错变 500。
            Math.multiplyExact(current - 1, size);
        } catch (ArithmeticException exception) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "分页偏移量超出允许范围");
        }
        String keyword = params.get("keyword");
        if (keyword != null) {
            // 原始长度先按 schema 校验，再 trim；空串不筛选。
            if (keyword.length() > 100) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "keyword 长度不能超过 100");
            }
            keyword = keyword.trim();
        }
        Integer status = parseStatus(params.get("status"));
        Long departmentId = params.containsKey("departmentId")
                ? parseId(params.get("departmentId")) : null;
        return new UserListQuery(current, size, keyword, departmentId, status);
    }

    private long parsePositiveLong(String raw, long defaultValue, long max) {
        if (raw == null) {
            return defaultValue;
        }
        if (!ID_PATTERN.matcher(raw).matches()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "分页参数必须是正整数");
        }
        long value;
        try {
            value = Long.parseLong(raw);
        } catch (NumberFormatException exception) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "分页参数超出允许范围");
        }
        if (value < 1 || value > max) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "分页参数超出允许范围");
        }
        return value;
    }

    private Integer parseStatus(String raw) {
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
