package com.xinghe.cms.security.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 接口权限标记（B 提供，全员使用）。包名遵循《跨模块服务接口》契约第 49 行：
 * 统一为 {@code com.xinghe.cms.security.annotation.RequirePermission}，
 * 其他模块按契约导入即可编译通过。
 *
 * <p>标注在 Controller 方法上，由 {@code RequirePermissionAspect} 在方法执行前校验
 * 当前用户的有效权限（数据来源 {@code AuthorizationQueryService#getForUser}）：
 * 未登录/会话失效/用户停用返回 401，已登录但缺少权限返回 403。</p>
 *
 * <p>使用规范（见 {@code docs/RequirePermission 使用规范.md}）：</p>
 * <ul>
 * <li>权限码必须与 OpenAPI {@code x-permission} 一致，一个方法只挂一个注解；</li>
 * <li>{@code value} 允许填多个权限码，语义为“任一满足即放行”（OR），
 *     需要同时满足多个权限的场景应拆分接口，而不是在同一方法上叠加要求；</li>
 * <li>PUBLIC / AUTHENTICATED 两类端点不挂本注解：公开端点无需鉴权，
 *     仅需登录的端点依赖认证过滤器自身的 401，不经过权限快照。</li>
 * </ul>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequirePermission {

    /** 允许通过本次请求的权限码（任一满足即可），必须与 OpenAPI x-permission 一致。 */
    String[] value();
}
