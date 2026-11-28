package com.xinghe.cms.log.controller;

import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import com.xinghe.cms.common.exception.BusinessException;
import com.xinghe.cms.common.request.QueryParams;
import com.xinghe.cms.common.response.ApiResponse;
import com.xinghe.cms.common.response.PageResult;
import com.xinghe.cms.common.response.ResultCode;
import com.xinghe.cms.log.dto.OperationLogQuery;
import com.xinghe.cms.log.service.OperationLogService;
import com.xinghe.cms.log.vo.OperationLogVO;
import com.xinghe.cms.security.annotation.RequirePermission;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ExtendedServletRequestDataBinder;

/**
 * 操作日志查询接口。
 *
 * <p>权限：列表要求 {@code log:list}、详情要求 {@code log:read}，由 B 的
 * {@link RequirePermission} 与 {@code RequirePermissionAspect} 统一校验：
 * 匿名/会话失效 401、已登录但缺少权限码 403、有权限 200。
 * 参数校验（未知参数、重复传值、十进制、19 位 ID 等）仍在本类内完成。</p>
 */
@RestController
@RequestMapping("/api/admin/logs")
@RequiredArgsConstructor
public class OperationLogController {

    /** 契约 8.1：列表接口只接受清单内的查询参数，其余一律 400。 */
    private static final Set<String> LIST_QUERY_PARAMS =
            Set.of("userId", "operation", "result", "createdFrom", "createdTo", "current", "size");

    /** 详情接口不声明任何查询参数：任何查询参数一律 400。 */
    private static final Set<String> DETAIL_QUERY_PARAMS = Set.of();

    /** MVC 绑定会接受十六进制及带前导零的数字，因此还需校验原始取值。 */
    private static final Set<String> DECIMAL_QUERY_PARAMS = Set.of("userId", "current", "size");

    /**
     * 契约 Id 定义：无前导零的正十进制，最多 19 位且不超过 {@link Long#MAX_VALUE}。
     * 19 位上限交给 {@link Long#parseLong(String)} 的溢出判断兜底。
     */
    private static final Pattern LOG_ID_PATTERN = Pattern.compile("[1-9][0-9]*");

    private final OperationLogService operationLogService;

    @InitBinder("operationLogQuery")
    void bindLogQuery(WebDataBinder binder) {
        if (binder instanceof ExtendedServletRequestDataBinder mvcBinder) {
            // 日志筛选只接受查询参数，请求头不能成为筛选条件。
            mvcBinder.addHeaderPredicate(name -> false);
        }
    }

    /**
     * 日志分页查询：条件全部为 AND，默认 createdAt DESC、id DESC。
     */
    @RequirePermission("log:list")
    @GetMapping
    public ApiResponse<PageResult<OperationLogVO>> page(@Valid @ModelAttribute OperationLogQuery query,
                                                        HttpServletRequest request) {
        Map<String, String[]> params = request.getParameterMap();
        rejectUnknownQueryParams(params.keySet(), LIST_QUERY_PARAMS);
        QueryParams.requireSingleValueForEachPresentKey(params);
        try {
            for (String name : DECIMAL_QUERY_PARAMS) {
                QueryParams.requireStrictDecimalValue(params, name);
            }
        } catch (BusinessException exception) {
            throw new BusinessException(ResultCode.BAD_REQUEST);
        }
        if ((params.containsKey("createdFrom") && query.getCreatedFrom() == null)
                || (params.containsKey("createdTo") && query.getCreatedTo() == null)) {
            throw new BusinessException(ResultCode.BAD_REQUEST);
        }
        return ApiResponse.success(operationLogService.page(query));
    }

    /**
     * 日志详情：不存在时由 Service 抛出 NOT_FOUND，统一异常处理返回 404。
     * 路径参数按十进制字符串接收，避免 Spring 把 0x1 解析成 1。
     */
    @RequirePermission("log:read")
    @GetMapping("/{id}")
    public ApiResponse<OperationLogVO> detail(@PathVariable String id, HttpServletRequest request) {
        rejectUnknownQueryParams(request.getParameterMap().keySet(), DETAIL_QUERY_PARAMS);
        Long logId = parseLogId(id);
        return ApiResponse.success(operationLogService.detail(logId));
    }

    /**
     * 拒绝未在契约中声明的查询参数，参数名按字典序拼接，保证错误信息稳定可断言。
     */
    private void rejectUnknownQueryParams(Set<String> actualParams, Set<String> allowedParams) {
        Set<String> unknown = new TreeSet<>();
        for (String name : actualParams) {
            if (!allowedParams.contains(name)) {
                unknown.add(name);
            }
        }
        if (!unknown.isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST,
                    "未知的查询参数：" + String.join("、", unknown));
        }
    }

    /**
     * 只接受无前导零的正十进制（1–19 位且不超过 {@link Long#MAX_VALUE}），
     * 避免十六进制、空白、负号、前导零或溢出值被当作合法 ID。
     */
    private Long parseLogId(String rawId) {
        if (rawId == null || !LOG_ID_PATTERN.matcher(rawId).matches()) {
            throw new BusinessException(ResultCode.BAD_REQUEST);
        }
        try {
            return Long.parseLong(rawId);
        } catch (NumberFormatException exception) {
            throw new BusinessException(ResultCode.BAD_REQUEST);
        }
    }
}
