package com.xinghe.cms.log.controller;

import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import com.xinghe.cms.common.exception.BusinessException;
import com.xinghe.cms.common.response.ApiResponse;
import com.xinghe.cms.common.response.PageResult;
import com.xinghe.cms.common.response.ResultCode;
import com.xinghe.cms.log.dto.OperationLogQuery;
import com.xinghe.cms.log.service.OperationLogService;
import com.xinghe.cms.log.vo.OperationLogVO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 操作日志查询接口。
 * 权限注解（log:list / log:read）待 B 的 @RequirePermission 合入 dev 后补充；
 * 当前不添加自建注解或恒放行实现。
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

    /** 需要按十进制校验的数值参数：Spring 默认接受 0x 前缀的十六进制，这里收紧为十进制。 */
    private static final Set<String> DECIMAL_QUERY_PARAMS = Set.of("userId", "current", "size");

    private static final Pattern DECIMAL_PATTERN = Pattern.compile("-?\\d+");

    private static final Pattern LOG_ID_PATTERN = Pattern.compile("[0-9]{1,18}");

    private final OperationLogService operationLogService;

    /**
     * 日志分页查询：条件全部为 AND，默认 createdAt DESC、id DESC。
     */
    @GetMapping
    public ApiResponse<PageResult<OperationLogVO>> page(@Valid @ModelAttribute OperationLogQuery query,
                                                        HttpServletRequest request) {
        rejectUnknownQueryParams(request, LIST_QUERY_PARAMS);
        return ApiResponse.success(operationLogService.page(query));
    }

    /**
     * 日志详情：不存在时由 Service 抛出 NOT_FOUND，统一异常处理返回 404。
     * 路径参数按十进制字符串接收，避免 Spring 把 0x1 解析成 1。
     */
    @GetMapping("/{id}")
    public ApiResponse<OperationLogVO> detail(@PathVariable String id, HttpServletRequest request) {
        rejectUnknownQueryParams(request, DETAIL_QUERY_PARAMS);
        return ApiResponse.success(operationLogService.detail(parseLogId(id)));
    }

    /**
     * 拒绝未在契约中声明的查询参数，参数名按字典序拼接，保证错误信息稳定可断言。
     */
    private void rejectUnknownQueryParams(HttpServletRequest request, Set<String> allowedParams) {
        Set<String> unknown = new TreeSet<>();
        for (String name : request.getParameterMap().keySet()) {
            if (!allowedParams.contains(name)) {
                unknown.add(name);
            }
        }
        if (!unknown.isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST,
                    "未知的查询参数：" + String.join("、", unknown));
        }
        for (String name : DECIMAL_QUERY_PARAMS) {
            String value = request.getParameter(name);
            if (value != null && !DECIMAL_PATTERN.matcher(value).matches()) {
                throw new BusinessException(ResultCode.BAD_REQUEST);
            }
        }
    }

    /**
     * 只接受 1–18 位十进制数字，避免十六进制、空白、负号或溢出值被当作合法 ID。
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
