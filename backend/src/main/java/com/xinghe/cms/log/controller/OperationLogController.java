package com.xinghe.cms.log.controller;

import java.util.Set;
import java.util.TreeSet;

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

    /** 契约 8.1：只接受清单内的查询参数，其余一律 400。 */
    private static final Set<String> ALLOWED_QUERY_PARAMS =
            Set.of("userId", "operation", "result", "createdFrom", "createdTo", "current", "size");

    private final OperationLogService operationLogService;

    /**
     * 日志分页查询：条件全部为 AND，默认 createdAt DESC、id DESC。
     */
    @GetMapping
    public ApiResponse<PageResult<OperationLogVO>> page(@Valid @ModelAttribute OperationLogQuery query,
                                                        HttpServletRequest request) {
        rejectUnknownQueryParams(request);
        return ApiResponse.success(operationLogService.page(query));
    }

    /**
     * 日志详情：不存在时由 Service 抛出 NOT_FOUND，统一异常处理返回 404。
     */
    @GetMapping("/{id}")
    public ApiResponse<OperationLogVO> detail(@PathVariable Long id, HttpServletRequest request) {
        rejectUnknownQueryParams(request);
        return ApiResponse.success(operationLogService.detail(id));
    }

    /**
     * 拒绝未在契约中声明的查询参数，参数名按字典序拼接，保证错误信息稳定可断言。
     */
    private void rejectUnknownQueryParams(HttpServletRequest request) {
        Set<String> unknown = new TreeSet<>();
        for (String name : request.getParameterMap().keySet()) {
            if (!ALLOWED_QUERY_PARAMS.contains(name)) {
                unknown.add(name);
            }
        }
        if (!unknown.isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST,
                    "未知的查询参数：" + String.join("、", unknown));
        }
    }
}
