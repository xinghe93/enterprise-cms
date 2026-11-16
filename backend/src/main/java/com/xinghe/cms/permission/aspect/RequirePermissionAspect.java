package com.xinghe.cms.permission.aspect;

import com.xinghe.cms.common.contract.AuthorizationSnapshot;
import com.xinghe.cms.common.contract.CurrentUser;
import com.xinghe.cms.common.exception.BusinessException;
import com.xinghe.cms.common.response.ResultCode;
import com.xinghe.cms.security.annotation.RequirePermission;
import com.xinghe.cms.permission.service.AuthorizationQueryService;
import com.xinghe.cms.security.CurrentUserProvider;
import java.util.Arrays;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.springframework.stereotype.Component;

/**
 * {@link RequirePermission} 的统一鉴权切面（B 模块 D13 交付，因依赖就绪提前接入）。
 *
 * <p>执行顺序：先经 A 的 {@link CurrentUserProvider#requireCurrentUser()} 确认请求身份
 * （无 Token/会话失效/用户停用均抛 401），再由 {@link AuthorizationQueryService#getForUser}
 * 实时计算授权快照（不缓存，权限变更对新请求立即生效），最后比对权限码——
 * 任一匹配即放行，全部未命中抛 403。</p>
 *
 * <p>注解 {@code value} 为空数组属于使用错误（无法表达“无需权限”的语义），
 * 按“失败关闭”处理直接拒绝，避免调用方漏填权限码时接口裸奔。</p>
 */
@Aspect
@Component
public class RequirePermissionAspect {

    private final CurrentUserProvider currentUserProvider;
    private final AuthorizationQueryService authorizationQueryService;

    public RequirePermissionAspect(CurrentUserProvider currentUserProvider,
            AuthorizationQueryService authorizationQueryService) {
        this.currentUserProvider = currentUserProvider;
        this.authorizationQueryService = authorizationQueryService;
    }

    /**
     * 在标注了 {@link RequirePermission} 的 Controller 方法执行前完成鉴权。
     *
     * @param joinPoint         被拦截的连接点，仅用于异常信息定位
     * @param requirePermission 方法上声明的权限要求
     */
    @Before("@annotation(requirePermission)")
    public void enforce(JoinPoint joinPoint, RequirePermission requirePermission) {
        String[] requiredCodes = requirePermission.value();
        if (requiredCodes.length == 0) {
            throw new BusinessException(ResultCode.INTERNAL_SERVER_ERROR,
                    "权限注解未配置权限码: " + joinPoint.getSignature().toShortString());
        }
        CurrentUser currentUser = currentUserProvider.requireCurrentUser();
        AuthorizationSnapshot snapshot = authorizationQueryService.getForUser(currentUser.userId());
        boolean permitted = Arrays.stream(requiredCodes)
                .anyMatch(snapshot.permissionCodes()::contains);
        if (!permitted) {
            throw new BusinessException(ResultCode.FORBIDDEN,
                    "缺少访问权限: " + String.join(", ", requiredCodes));
        }
    }
}
