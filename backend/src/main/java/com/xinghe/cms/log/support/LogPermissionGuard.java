package com.xinghe.cms.log.support;

import com.xinghe.cms.common.contract.AuthorizationSnapshot;
import com.xinghe.cms.common.contract.CurrentUser;
import com.xinghe.cms.common.exception.BusinessException;
import com.xinghe.cms.common.response.ResultCode;
import com.xinghe.cms.permission.service.AuthorizationQueryService;
import com.xinghe.cms.security.CurrentUserProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 日志接口的功能权限校验：先取 A 的安全身份，再按 B 的授权快照判断权限码。
 *
 * <p>为什么在路由层显式判断而不是直接写 {@code @RequirePermission}：B 的权限注解与 AOP
 * 目前尚未合入 {@code dev}（{@code dev} 上只有 {@code AuthorizationQueryService} 数据层）。
 * 这里只用 A/B 已交付的公共入口，不新建身份或权限体系；B 的注解合入后，这两个调用可
 * 直接替换为 {@code @RequirePermission("log:list")} / {@code @RequirePermission("log:read")}。</p>
 *
 * <p>未登录（无已验证身份）抛 401；已登录但缺少权限码抛 403；权限码只包含在授权快照的
 * {@code permissionCodes} 中时放行，不读取会话里的旧角色。</p>
 */
@Component
@RequiredArgsConstructor
public class LogPermissionGuard {

    private final CurrentUserProvider currentUserProvider;

    private final AuthorizationQueryService authorizationQueryService;

    public void require(String permissionCode) {
        CurrentUser currentUser = currentUserProvider.requireCurrentUser();
        AuthorizationSnapshot snapshot = authorizationQueryService.getForUser(currentUser.userId());
        if (!snapshot.permissionCodes().contains(permissionCode)) {
            throw new BusinessException(ResultCode.FORBIDDEN);
        }
    }
}
