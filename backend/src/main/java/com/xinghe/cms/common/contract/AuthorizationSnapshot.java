package com.xinghe.cms.common.contract;

import java.util.Set;

/**
 * 用户授权快照：单次请求内鉴权所依据的有效角色与权限集合。
 *
 * <p>由 B 模块的 {@code AuthorizationQueryService.getForUser(Long userId)} 计算并返回：
 * {@code enabledRoleCodes} 只包含启用用户关联的启用角色（无权限的启用角色也在其中）；
 * {@code permissionCodes} 在此基础上再过滤停用权限。用户不存在或已停用时不构造快照，
 * 而是抛出 UNAUTHORIZED，避免把空快照误认为已认证。</p>
 *
 * <p>跨模块契约（见《跨模块服务接口 V1.0》）：C/D 判断 ADMIN 全体范围时只能检查本快照，
 * 不得检查会话中的旧角色或用于管理回显的 User.roles。集合在构造时防御性复制，
 * 外部无法修改快照内容，也不随请求外的状态变化。</p>
 *
 * @param enabledRoleCodes 有效角色编码集合，非 null，可能为空
 * @param permissionCodes  有效权限编码集合，非 null，可能为空
 */
public record AuthorizationSnapshot(Set<String> enabledRoleCodes, Set<String> permissionCodes) {

    public AuthorizationSnapshot {
        java.util.Objects.requireNonNull(enabledRoleCodes, "enabledRoleCodes 不能为 null");
        java.util.Objects.requireNonNull(permissionCodes, "permissionCodes 不能为 null");
        enabledRoleCodes = Set.copyOf(enabledRoleCodes);
        permissionCodes = Set.copyOf(permissionCodes);
    }
}
