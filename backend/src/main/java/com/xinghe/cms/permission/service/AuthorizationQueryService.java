package com.xinghe.cms.permission.service;

import com.xinghe.cms.common.contract.AuthorizationSnapshot;

/**
 * 授权快照查询服务（B 模块对外契约，见《跨模块服务接口 V1.0》第 2 节）。
 *
 * <p>每个 HTTP 请求从数据库重新计算有效授权，不做跨请求缓存；
 * 首期鉴权链路（{@code @RequirePermission} AOP）依赖本服务的结果。</p>
 */
public interface AuthorizationQueryService {

    /**
     * 计算指定用户的授权快照。
     *
     * <p>{@code enabledRoleCodes} 只含启用用户关联的启用角色（含没有任何权限的启用角色）；
     * {@code permissionCodes} 在此基础上再过滤停用权限。用户没有有效角色时两者均为空集合。</p>
     *
     * @param userId 用户 ID（由 A 的身份入口提供，不是请求参数）
     * @return 不可变的授权快照；集合永不为 null
     * @throws com.xinghe.cms.common.exception.BusinessException 用户不存在或已停用时抛
     *         {@code UNAUTHORIZED}，绝不返回可被误认为已认证的空快照
     */
    AuthorizationSnapshot getForUser(Long userId);
}
