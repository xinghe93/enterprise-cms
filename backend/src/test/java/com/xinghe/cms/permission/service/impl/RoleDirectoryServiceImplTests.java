package com.xinghe.cms.permission.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.xinghe.cms.common.contract.RoleRef;
import com.xinghe.cms.common.exception.BusinessException;
import com.xinghe.cms.common.response.ResultCode;
import com.xinghe.cms.permission.entity.SysRole;
import com.xinghe.cms.permission.mapper.SysRoleMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link RoleDirectoryServiceImpl} 单测：空集合、去重排序、404/409、事务传播语义。
 * 共享锁行为（FOR SHARE 持锁到事务结束）需真实数据库，属联调验证范围。
 */
@ExtendWith(MockitoExtension.class)
class RoleDirectoryServiceImplTests {

    @Mock
    private SysRoleMapper sysRoleMapper;

    @InjectMocks
    private RoleDirectoryServiceImpl service;

    @Test
    void requiresMandatoryTransactionSemantics() throws NoSuchMethodException {
        // 共享锁必须与调用方（A 的关联写入）处于同一事务，故要求 MANDATORY
        Transactional annotation = RoleDirectoryServiceImpl.class
                .getMethod("lockAssignableRoles", List.class)
                .getAnnotation(Transactional.class);

        assertThat(annotation).isNotNull();
        assertThat(annotation.propagation()).isEqualTo(Propagation.MANDATORY);
    }

    @Test
    void returnsEmptyListForEmptyInputWithoutQuerying() {
        assertThat(service.lockAssignableRoles(List.of())).isEmpty();
        assertThat(service.lockAssignableRoles(null)).isEmpty();

        verifyNoInteractions(sysRoleMapper);
    }

    @Test
    void deduplicatesAndSortsIdsBeforeLocking() {
        when(sysRoleMapper.selectByIdsForShare(List.of(3L, 7L)))
                .thenReturn(List.of(role(7L, "EDITOR", 1), role(3L, "EMPLOYEE", 1)));

        List<RoleRef> result = service.lockAssignableRoles(List.of(7L, 3L, 7L));

        verify(sysRoleMapper).selectByIdsForShare(List.of(3L, 7L));
        // 返回按 ID 升序，便于调用方稳定展示
        assertThat(result).extracting(RoleRef::id).containsExactly(3L, 7L);
        assertThat(result).extracting(RoleRef::roleCode).containsExactly("EMPLOYEE", "EDITOR");
    }

    @Test
    void rejectsNullElementsOnly() {
        assertThatThrownBy(() -> service.lockAssignableRoles(java.util.Arrays.asList(null, null)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getResultCode())
                .isEqualTo(ResultCode.BAD_REQUEST);

        verifyNoInteractions(sysRoleMapper);
    }

    @Test
    void rejectsMissingRoleWith404() {
        when(sysRoleMapper.selectByIdsForShare(List.of(1L, 2L)))
                .thenReturn(List.of(role(1L, "EMPLOYEE", 1)));

        assertThatThrownBy(() -> service.lockAssignableRoles(List.of(1L, 2L)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getResultCode())
                .isEqualTo(ResultCode.NOT_FOUND);
    }

    @Test
    void rejectsDisabledRoleWith409() {
        when(sysRoleMapper.selectByIdsForShare(anyList()))
                .thenReturn(List.of(role(1L, "EMPLOYEE", 1), role(2L, "EDITOR", 0)));

        assertThatThrownBy(() -> service.lockAssignableRoles(List.of(1L, 2L)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getResultCode())
                .isEqualTo(ResultCode.CONFLICT);
    }

    @Test
    void mapsLockedRolesToRefs() {
        when(sysRoleMapper.selectByIdsForShare(List.of(5L)))
                .thenReturn(List.of(role(5L, "ADMIN", 1)));

        List<RoleRef> result = service.lockAssignableRoles(List.of(5L));

        assertThat(result).containsExactly(new RoleRef(5L, "ADMIN", "系统管理员"));
    }

    private static SysRole role(Long id, String code, int status) {
        SysRole role = new SysRole();
        role.setId(id);
        role.setRoleCode(code);
        role.setRoleName("ADMIN".equals(code) ? "系统管理员" : code);
        role.setStatus(status);
        return role;
    }
}
