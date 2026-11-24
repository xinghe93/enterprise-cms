package com.xinghe.cms.common.contract;

import java.util.Objects;

/**
 * 已通过认证的请求身份（跨模块共享值对象，见《跨模块服务接口 V1.0》第 1 节）。
 *
 * <p>由 A 的 {@code security.CurrentUserProvider} 提供，只能从服务端已验证的
 * Bearer 会话构造；不得由请求参数中的 userId 生成。departmentId 允许为 null。</p>
 *
 * <p>本对象只承载最小身份信息，不包含角色或权限。有效角色与权限一律由 B 的
 * {@code AuthorizationQueryService.getForUser(userId)} 返回的授权快照计算。</p>
 */
public record CurrentUser(Long userId, String username, Long departmentId) {

    public CurrentUser {
        Objects.requireNonNull(userId, "userId 不能为 null");
        Objects.requireNonNull(username, "username 不能为 null");
    }
}
