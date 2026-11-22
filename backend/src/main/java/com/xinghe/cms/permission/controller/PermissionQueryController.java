package com.xinghe.cms.permission.controller;

import com.xinghe.cms.common.contract.AuthorizationSnapshot;
import com.xinghe.cms.common.contract.CurrentUser;
import com.xinghe.cms.common.response.ApiResponse;
import com.xinghe.cms.permission.service.AuthorizationQueryService;
import com.xinghe.cms.security.CurrentUserProvider;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 当前登录用户的权限编码查询（OpenAPI {@code GET /api/auth/permissions}）。
 *
 * <p>契约 owner 为 B 模块（见 docs/api/enterprise-cms.openapi.json 的 {@code x-owner}），
 * 此前只有契约、没有实现，导致前端无法在登录后拿到 {@code permissionCodes}：
 * 审核工作台、日志页与 C 的文章详情页都按权限码门控，缺少该接口时审核员
 * 从待审队列点进文章详情会因 {@code article:read} 未知而不请求正文。</p>
 *
 * <p>本实现只做最小必要组合：身份来自 A 的 {@link CurrentUserProvider}，
 * 权限由 B 的 {@link AuthorizationQueryService#getForUser(Long)} 实时计算
 * （不缓存、不从 {@code User.roles} 推断），返回不可变列表；B 后续如需扩展
 * 响应结构可原地替换，前端契约不变。</p>
 */
@RestController
@RequestMapping("/api/auth")
public class PermissionQueryController {

    private final CurrentUserProvider currentUserProvider;

    private final AuthorizationQueryService authorizationQueryService;

    public PermissionQueryController(CurrentUserProvider currentUserProvider,
                                     AuthorizationQueryService authorizationQueryService) {
        this.currentUserProvider = currentUserProvider;
        this.authorizationQueryService = authorizationQueryService;
    }

    /**
     * 返回当前会话用户的有效权限编码（停用角色/权限已被过滤）。
     *
     * <p>未登录或会话失效由 {@link CurrentUserProvider} 抛 401；
     * 用户被停用由授权服务抛 401；无任何有效权限时返回空数组，前端按
     * "已加载但为空"处理（隐藏所有需要权限码的操作）。</p>
     */
    @GetMapping("/permissions")
    public ApiResponse<List<String>> permissions() {
        CurrentUser currentUser = currentUserProvider.requireCurrentUser();
        AuthorizationSnapshot snapshot = authorizationQueryService.getForUser(currentUser.userId());
        // 授权快照用 Set 保存、迭代顺序不保证；接口按 OpenAPI 约定输出自然序（字典升序），
        // 便于前端/测试与文档示例保持一致。
        List<String> codes = snapshot.permissionCodes().stream().sorted().toList();
        return ApiResponse.success(codes);
    }
}
