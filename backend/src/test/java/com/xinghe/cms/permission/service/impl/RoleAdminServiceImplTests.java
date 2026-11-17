package com.xinghe.cms.permission.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.xinghe.cms.common.exception.BusinessException;
import com.xinghe.cms.common.response.ResultCode;
import com.xinghe.cms.permission.entity.SysRole;
import com.xinghe.cms.permission.mapper.SysRoleMapper;
import com.xinghe.cms.permission.request.RoleCreateRequest;
import com.xinghe.cms.permission.response.RoleView;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

/**
 * {@link RoleAdminServiceImpl} 创建角色行为单测：默认状态、码点长度校验、
 * 唯一键冲突转 409、数据库时间回查。SQL 与唯一约束本身由联调阶段集成验证。
 */
@ExtendWith(MockitoExtension.class)
class RoleAdminServiceImplTests {

    @Mock
    private SysRoleMapper sysRoleMapper;

    @InjectMocks
    private RoleAdminServiceImpl service;

    private static SysRole savedRole(long id, String code, String name) {
        SysRole entity = new SysRole();
        entity.setId(id);
        entity.setRoleCode(code);
        entity.setRoleName(name);
        entity.setStatus(1);
        entity.setCreatedAt(LocalDateTime.of(2026, 11, 17, 17, 40, 0, 0));
        entity.setUpdatedAt(LocalDateTime.of(2026, 11, 17, 17, 40, 0, 0));
        return entity;
    }

    /** 创建成功：status 固定 1，ID 以十进制字符串返回，时间为数据库回查值。 */
    @Test
    void createSuccessReturnsFullViewWithDefaultStatus() {
        doAnswer(invocation -> {
            SysRole entity = invocation.getArgument(0);
            entity.setId(77L);
            return 1;
        }).when(sysRoleMapper).insert(any(SysRole.class));
        when(sysRoleMapper.selectById(77L)).thenReturn(savedRole(77L, "CUSTOM_EDITOR", "自定义编辑"));

        RoleView view = service.create(new RoleCreateRequest("CUSTOM_EDITOR", "自定义编辑"));

        assertThat(view.id()).isEqualTo("77");
        assertThat(view.roleCode()).isEqualTo("CUSTOM_EDITOR");
        assertThat(view.roleName()).isEqualTo("自定义编辑");
        assertThat(view.status()).isEqualTo(1);
        assertThat(view.createdAt()).isEqualTo(LocalDateTime.of(2026, 11, 17, 17, 40, 0, 0));
        assertThat(view.updatedAt()).isEqualTo(LocalDateTime.of(2026, 11, 17, 17, 40, 0, 0));

        ArgumentCaptor<SysRole> captor = ArgumentCaptor.forClass(SysRole.class);
        verify(sysRoleMapper).insert(captor.capture());
        assertThat(captor.getValue().getRoleCode()).isEqualTo("CUSTOM_EDITOR");
        assertThat(captor.getValue().getRoleName()).isEqualTo("自定义编辑");
        assertThat(captor.getValue().getStatus()).isEqualTo(1);
    }

    /** roleCode 重复（唯一键冲突）转 409，不落入 500 兜底。 */
    @Test
    void duplicateRoleCodeBecomes409() {
        doThrow(new DuplicateKeyException("Duplicate entry 'EDITOR'"))
                .when(sysRoleMapper).insert(any(SysRole.class));

        assertThatThrownBy(() -> service.create(new RoleCreateRequest("EDITOR", "编辑")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getResultCode())
                .isEqualTo(ResultCode.CONFLICT);
    }

    /** roleCode 恰好 64 个码点放行（OpenAPI maxLength 按字符数计）。 */
    @Test
    void roleCodeWith64CodePointsIsAccepted() {
        String code = "编".repeat(64);
        doAnswer(invocation -> {
            SysRole entity = invocation.getArgument(0);
            entity.setId(78L);
            return 1;
        }).when(sysRoleMapper).insert(any(SysRole.class));
        when(sysRoleMapper.selectById(78L)).thenReturn(savedRole(78L, code, "名称"));

        RoleView view = service.create(new RoleCreateRequest(code, "名称"));

        assertThat(view.roleCode()).isEqualTo(code);
    }

    /** roleCode 65 个码点拒绝（String.length 同为 65，此处验证码点边界本身）。 */
    @Test
    void roleCodeBeyond64CodePointsRejected() {
        String code = "编".repeat(65);

        assertThatThrownBy(() -> service.create(new RoleCreateRequest(code, "名称")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getResultCode())
                .isEqualTo(ResultCode.BAD_REQUEST);
    }

    /**
     * 增补字符按码点计数：64 个 emoji（String.length()=128）是合法长度，
     * 65 个（String.length()=130）拒绝——若按 UTF-16 计数会误拒合法值。
     */
    @Test
    void supplementaryCharactersCountAsOneCodePoint() {
        String ok = "\uD83C\uDF00".repeat(64);
        doAnswer(invocation -> {
            SysRole entity = invocation.getArgument(0);
            entity.setId(79L);
            return 1;
        }).when(sysRoleMapper).insert(any(SysRole.class));
        when(sysRoleMapper.selectById(79L)).thenReturn(savedRole(79L, ok, "名称"));

        assertThat(service.create(new RoleCreateRequest(ok, "名称")).roleCode()).isEqualTo(ok);

        String tooLong = "\uD83C\uDF00".repeat(65);
        assertThatThrownBy(() -> service.create(new RoleCreateRequest(tooLong, "名称")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getResultCode())
                .isEqualTo(ResultCode.BAD_REQUEST);
    }

    /** roleName 超过 100 个码点拒绝。 */
    @Test
    void roleNameBeyond100CodePointsRejected() {
        assertThatThrownBy(() -> service.create(new RoleCreateRequest("CUSTOM", "名".repeat(101))))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getResultCode())
                .isEqualTo(ResultCode.BAD_REQUEST);
    }
}
