package com.xinghe.cms.permission.aspect;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.xinghe.cms.common.contract.AuthorizationSnapshot;
import com.xinghe.cms.common.contract.CurrentUser;
import com.xinghe.cms.common.exception.BusinessException;
import com.xinghe.cms.common.response.ResultCode;
import com.xinghe.cms.security.annotation.RequirePermission;
import com.xinghe.cms.permission.service.AuthorizationQueryService;
import com.xinghe.cms.security.CurrentUserProvider;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link RequirePermissionAspect} 的鉴权行为回归：
 * 401 优先于 403、快照实时查询、任一权限码匹配即放行、空权限码失败关闭。
 */
@ExtendWith(MockitoExtension.class)
class RequirePermissionAspectTests {

    @Mock
    private CurrentUserProvider currentUserProvider;

    @Mock
    private AuthorizationQueryService authorizationQueryService;

    private RequirePermissionAspect aspect;

    @BeforeEach
    void setUp() {
        aspect = new RequirePermissionAspect(currentUserProvider, authorizationQueryService);
    }

    private RequirePermission annotation(String... codes) {
        return new RequirePermission() {
            @Override
            public Class<? extends java.lang.annotation.Annotation> annotationType() {
                return RequirePermission.class;
            }

            @Override
            public String[] value() {
                return codes;
            }
        };
    }

    private CurrentUser user() {
        return new CurrentUser(37L, "editor01", null);
    }

    private AuthorizationSnapshot snapshot(String... permissionCodes) {
        return new AuthorizationSnapshot(Set.of("EMPLOYEE"), Set.of(permissionCodes));
    }

    @Test
    void permitsWhenSnapshotContainsRequiredCode() {
        when(currentUserProvider.requireCurrentUser()).thenReturn(user());
        when(authorizationQueryService.getForUser(37L))
                .thenReturn(snapshot("role:list", "role:read"));

        assertThatCode(() -> aspect.enforce(null, annotation("role:list")))
                .doesNotThrowAnyException();

        verify(authorizationQueryService).getForUser(37L);
    }

    @Test
    void permitsAnyOfMultipleRequiredCodes() {
        when(currentUserProvider.requireCurrentUser()).thenReturn(user());
        when(authorizationQueryService.getForUser(37L)).thenReturn(snapshot("role:read"));

        assertThatCode(() -> aspect.enforce(null, annotation("role:list", "role:read")))
                .doesNotThrowAnyException();
    }

    @Test
    void deniesWithForbiddenWhenCodeMissing() {
        when(currentUserProvider.requireCurrentUser()).thenReturn(user());
        when(authorizationQueryService.getForUser(37L)).thenReturn(snapshot("role:list"));

        assertThatThrownBy(() -> aspect.enforce(null, annotation("role:assign-permission")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getResultCode())
                .isEqualTo(ResultCode.FORBIDDEN);
    }

    @Test
    void deniesWhenSnapshotHasNoPermissions() {
        when(currentUserProvider.requireCurrentUser()).thenReturn(user());
        when(authorizationQueryService.getForUser(37L))
                .thenReturn(new AuthorizationSnapshot(Set.of("EMPLOYEE"), Set.of()));

        assertThatThrownBy(() -> aspect.enforce(null, annotation("role:list")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getResultCode())
                .isEqualTo(ResultCode.FORBIDDEN);
    }

    @Test
    void propagatesUnauthorizedBeforeTouchingSnapshot() {
        when(currentUserProvider.requireCurrentUser())
                .thenThrow(new BusinessException(ResultCode.UNAUTHORIZED));

        assertThatThrownBy(() -> aspect.enforce(null, annotation("role:list")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getResultCode())
                .isEqualTo(ResultCode.UNAUTHORIZED);

        verifyNoInteractions(authorizationQueryService);
    }

    @Test
    void failsClosedOnEmptyCodes() {
        org.aspectj.lang.JoinPoint joinPoint = mock(org.aspectj.lang.JoinPoint.class);
        when(joinPoint.getSignature()).thenReturn(mock(org.aspectj.lang.Signature.class));

        assertThatThrownBy(() -> aspect.enforce(joinPoint, annotation()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getResultCode())
                .isEqualTo(ResultCode.INTERNAL_SERVER_ERROR);

        verifyNoInteractions(currentUserProvider, authorizationQueryService);
    }
}
