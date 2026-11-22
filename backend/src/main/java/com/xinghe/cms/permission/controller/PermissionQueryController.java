package com.xinghe.cms.permission.controller;

import com.xinghe.cms.common.contract.AuthorizationSnapshot;
import com.xinghe.cms.common.contract.CurrentUser;
import com.xinghe.cms.common.request.QueryParams;
import com.xinghe.cms.common.response.ApiResponse;
import com.xinghe.cms.permission.service.AuthorizationQueryService;
import com.xinghe.cms.security.CurrentUserProvider;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Set;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 当前登录用户的权限编码查询（OpenAPI {@code GET /api/auth/permissions}，
 * 契约 owner 为 B，见 {@code enterprise-cms.openapi.json} 的 {@code x-owner}）。
 *
 * <p>契约背景：接口 owner 是 B 模块，但此前只有契约、没有实现，导致前端无法在
 * 登录后拿到 {@code permissionCodes}——审核工作台、日志页与 C 的文章详情页都按
 * 权限码门控，缺少该接口时审核员从待审队列点进文章详情会因 {@code article:read}
 * 未知而不请求正文。该端点最初由 D 在 review 交付中最小代写，B 已认领并按契约
 * 加固（与 D 同步过）：本实现只做最小必要组合，B 如需扩展响应结构可原地替换，
 * 前端契约不变。</p>
 *
 * <p>鉴权：{@code x-permission} 为 {@code AUTHENTICATED}——只要求登录、不要求
 * 功能权限项（{@code PUBLIC}/{@code AUTHENTICATED} 是文档标记，不是权限表记录）。
 * 身份来自 A 的 {@link CurrentUserProvider}（已验证会话，不接受请求传入的身份）；
 * 权限由 B 的 {@link AuthorizationQueryService#getForUser(Long)} 实时计算（不缓存、
 * 不从 {@code User.roles} 推断），返回不可变列表。未登录/会话失效在 A 的过滤器
 * 即 401；登录后用户被停用由授权服务抛 401。</p>
 *
 * <p>查询参数按契约 8.x 严格校验（协作约定第 8 节：未知查询参数一律 400）：
 * OpenAPI 未为本端点定义任何 parameters，任何查询键都按未知参数拒绝，且校验
 * 发生在触达授权服务之前。响应编码由快照集合去重（OpenAPI {@code uniqueItems}），
 * 按字符串自然序输出；无任何有效权限时返回空数组，前端按"已加载但为空"处理
 * （隐藏所有需要权限码的操作）。</p>
 */
@RestController
@RequestMapping("/api/auth")
public class PermissionQueryController {

    /** 只读接口不接受任何查询参数（OpenAPI 未定义 parameters，契约 8.x：之外的键一律 400）。 */
    private static final Set<String> NO_QUERY_PARAMS = Set.of();

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
    public ApiResponse<List<String>> permissions(HttpServletRequest request) {
        QueryParams.requireAllowedKeys(request.getParameterMap(), NO_QUERY_PARAMS);
        CurrentUser currentUser = currentUserProvider.requireCurrentUser();
        AuthorizationSnapshot snapshot = authorizationQueryService.getForUser(currentUser.userId());
        // 授权快照用 Set 保存、迭代顺序不保证；接口按 OpenAPI 约定输出自然序（字典升序），
        // 便于前端/测试与文档示例保持一致。
        List<String> codes = snapshot.permissionCodes().stream().sorted().toList();
        return ApiResponse.success(codes);
    }
}
