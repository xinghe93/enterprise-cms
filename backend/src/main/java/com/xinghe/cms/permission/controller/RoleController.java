package com.xinghe.cms.permission.controller;

import com.xinghe.cms.common.request.Ids;
import com.xinghe.cms.common.request.PageQuery;
import com.xinghe.cms.common.response.ApiResponse;
import com.xinghe.cms.common.response.PageResult;
import com.xinghe.cms.permission.response.RoleView;
import com.xinghe.cms.permission.service.RoleQueryService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 角色管理接口。当前批次仅提供只读查询（D5）；
 * 创建/修改/启停在后续分支补充。
 *
 * <p>鉴权说明：按契约本接口需要 {@code role:list} / {@code role:read} 权限，
 * 鉴权 AOP 由 D13 统一接入（依赖 A 模块身份入口），当前阶段暂不挂注解。</p>
 */
@RestController
@RequestMapping("/api/admin/roles")
public class RoleController {

    private final RoleQueryService roleQueryService;

    public RoleController(RoleQueryService roleQueryService) {
        this.roleQueryService = roleQueryService;
    }

    /**
     * 角色分页：keyword 对 roleCode/roleName 做字面包含匹配，status 精确筛选。
     */
    @GetMapping
    public ApiResponse<PageResult<RoleView>> page(@Valid PageQuery query,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer status) {
        return ApiResponse.success(roleQueryService.page(query, keyword, status));
    }

    /**
     * 角色详情。路径 ID 遵循契约的十进制字符串规则，非法格式返回 400。
     */
    @GetMapping("/{id}")
    public ApiResponse<RoleView> detail(@PathVariable String id) {
        return ApiResponse.success(roleQueryService.getById(Ids.parse(id)));
    }
}
