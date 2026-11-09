package com.xinghe.cms.permission.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.xinghe.cms.common.contract.AuthorizationSnapshot;
import com.xinghe.cms.common.exception.BusinessException;
import com.xinghe.cms.common.response.ResultCode;
import com.xinghe.cms.permission.mapper.AuthorizationQueryMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link AuthorizationQueryServiceImpl} 行为单测：验证 UNAUTHORIZED 边界与快照组装规则。
 * SQL 正确性由 Mapper 的 @Select 保证，集成验证在联调阶段进行。
 */
@ExtendWith(MockitoExtension.class)
class AuthorizationQueryServiceImplTests {

    @Mock
    private AuthorizationQueryMapper authorizationQueryMapper;

    @InjectMocks
    private AuthorizationQueryServiceImpl service;

    @Test
    void rejectsNullUserIdWithoutTouchingMapper() {
        assertThatThrownBy(() -> service.getForUser(null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getResultCode())
                .isEqualTo(ResultCode.UNAUTHORIZED);

        verifyNoInteractions(authorizationQueryMapper);
    }

    @Test
    void rejectsMissingUser() {
        when(authorizationQueryMapper.selectUserStatus(1L)).thenReturn(null);

        assertThatThrownBy(() -> service.getForUser(1L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getResultCode())
                .isEqualTo(ResultCode.UNAUTHORIZED);
    }

    @Test
    void rejectsDisabledUser() {
        when(authorizationQueryMapper.selectUserStatus(2L)).thenReturn(0);

        assertThatThrownBy(() -> service.getForUser(2L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getResultCode())
                .isEqualTo(ResultCode.UNAUTHORIZED);
    }

    @Test
    void buildsSnapshotFromEnabledRolesAndPermissions() {
        when(authorizationQueryMapper.selectUserStatus(3L)).thenReturn(1);
        when(authorizationQueryMapper.selectEnabledRoleCodes(3L))
                .thenReturn(List.of("EMPLOYEE", "REVIEWER"));
        when(authorizationQueryMapper.selectPermissionCodes(3L))
                .thenReturn(List.of("review:approve", "role:list"));

        AuthorizationSnapshot snapshot = service.getForUser(3L);

        // 集合语义：内容一致即可，排序由 SQL 的 ORDER BY 负责
        assertThat(snapshot.enabledRoleCodes()).containsExactlyInAnyOrder("EMPLOYEE", "REVIEWER");
        assertThat(snapshot.permissionCodes()).containsExactlyInAnyOrder("review:approve", "role:list");
        // 快照不可变：外部修改必须失败（record 内部已防御性复制）
        assertThatThrownBy(() -> snapshot.enabledRoleCodes().add("ADMIN"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void keepsEnabledRoleWithoutAnyPermission() {
        when(authorizationQueryMapper.selectUserStatus(4L)).thenReturn(1);
        when(authorizationQueryMapper.selectEnabledRoleCodes(4L)).thenReturn(List.of("EDITOR"));
        when(authorizationQueryMapper.selectPermissionCodes(4L)).thenReturn(List.of());

        AuthorizationSnapshot snapshot = service.getForUser(4L);

        // 启用但没有权限的角色仍出现在 enabledRoleCodes，权限集合为空
        assertThat(snapshot.enabledRoleCodes()).containsExactly("EDITOR");
        assertThat(snapshot.permissionCodes()).isEmpty();
    }

    @Test
    void returnsEmptySnapshotWhenUserHasNoRoles() {
        when(authorizationQueryMapper.selectUserStatus(5L)).thenReturn(1);
        when(authorizationQueryMapper.selectEnabledRoleCodes(5L)).thenReturn(List.of());
        when(authorizationQueryMapper.selectPermissionCodes(5L)).thenReturn(List.of());

        AuthorizationSnapshot snapshot = service.getForUser(5L);

        // 用户有效但无角色：合法的空快照，不是异常
        assertThat(snapshot.enabledRoleCodes()).isEmpty();
        assertThat(snapshot.permissionCodes()).isEmpty();
    }
}
