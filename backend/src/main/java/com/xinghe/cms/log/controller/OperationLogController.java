package com.xinghe.cms.log.controller;

import com.xinghe.cms.common.response.ApiResponse;
import com.xinghe.cms.common.response.PageResult;
import com.xinghe.cms.log.dto.OperationLogQuery;
import com.xinghe.cms.log.service.OperationLogService;
import com.xinghe.cms.log.vo.OperationLogVO;
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

    private final OperationLogService operationLogService;

    /**
     * 日志分页查询：条件全部为 AND，默认 createdAt DESC、id DESC。
     */
    @GetMapping
    public ApiResponse<PageResult<OperationLogVO>> page(@Valid @ModelAttribute OperationLogQuery query) {
        return ApiResponse.success(operationLogService.page(query));
    }

    /**
     * 日志详情：不存在时由 Service 抛出 NOT_FOUND，统一异常处理返回 404。
     */
    @GetMapping("/{id}")
    public ApiResponse<OperationLogVO> detail(@PathVariable Long id) {
        return ApiResponse.success(operationLogService.detail(id));
    }
}
