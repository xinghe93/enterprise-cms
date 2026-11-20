package com.xinghe.cms.permission.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.xinghe.cms.common.exception.BusinessException;
import com.xinghe.cms.common.response.ResultCode;
import com.xinghe.cms.permission.entity.SysRole;
import com.xinghe.cms.permission.mapper.SysRoleMapper;
import com.xinghe.cms.permission.request.RoleCreateRequest;
import com.xinghe.cms.permission.request.RoleStatusRequest;
import com.xinghe.cms.permission.request.RoleUpdateRequest;
import com.xinghe.cms.permission.response.RoleView;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import static org.mockito.Mockito.never;

/**
 * {@link RoleAdminServiceImpl} 写服务单测：创建（默认状态、码点长度、唯一键 409、
 * 规范化写库）、修改（只改 roleName、404、规范化）、启停（幂等 200、404、非法取值）。
 * SQL 与唯一约束本身由联调阶段集成验证。
 */
@ExtendWith(MockitoExtension.class)
class RoleAdminServiceImplTests {

    @Mock
    private SysRoleMapper sysRoleMapper;

    @Mock
    private RoleWriteAuditMarker roleWriteAuditMarker;

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

    /** 评审 P2：写库前去除首尾空白——" EDITOR " 规范化为 EDITOR，撞唯一索引返回 409。 */
    @Test
    void whitespacePaddedRoleCodeCollidesWithExistingCode() {
        doThrow(new DuplicateKeyException("Duplicate entry 'EDITOR'"))
                .when(sysRoleMapper).insert(any(SysRole.class));

        assertThatThrownBy(() -> service.create(new RoleCreateRequest("  EDITOR  ", "编辑")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getResultCode())
                .isEqualTo(ResultCode.CONFLICT);

        ArgumentCaptor<SysRole> captor = ArgumentCaptor.forClass(SysRole.class);
        verify(sysRoleMapper).insert(captor.capture());
        assertThat(captor.getValue().getRoleCode()).isEqualTo("EDITOR");
    }

    /** 评审 P2：roleCode/roleName 均去除首尾空白后写库，响应返回规范化值。 */
    @Test
    void trimsBothFieldsBeforeWrite() {
        doAnswer(invocation -> {
            SysRole entity = invocation.getArgument(0);
            entity.setId(80L);
            return 1;
        }).when(sysRoleMapper).insert(any(SysRole.class));
        when(sysRoleMapper.selectById(80L))
                .thenReturn(savedRole(80L, "CUSTOM", "自定义编辑"));

        RoleView view = service.create(new RoleCreateRequest(" CUSTOM ", "  自定义编辑\t"));

        assertThat(view.roleCode()).isEqualTo("CUSTOM");
        assertThat(view.roleName()).isEqualTo("自定义编辑");

        ArgumentCaptor<SysRole> captor = ArgumentCaptor.forClass(SysRole.class);
        verify(sysRoleMapper).insert(captor.capture());
        assertThat(captor.getValue().getRoleCode()).isEqualTo("CUSTOM");
        assertThat(captor.getValue().getRoleName()).isEqualTo("自定义编辑");
    }

    /** 协作约定 8.1：先校验原始长度再去空白——带空格的 65 码点输入拒绝，即使清理后只有 62。 */
    @Test
    void rawLengthIsCheckedBeforeTrimming() {
        String raw = "  " + "a".repeat(62) + " ";

        assertThatThrownBy(() -> service.create(new RoleCreateRequest(raw, "名称")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getResultCode())
                .isEqualTo(ResultCode.BAD_REQUEST);
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

    // ---------- 修改角色（PUT /{id}） ----------

    /** 修改成功：只改 roleName，编码与状态保持不变，返回完整 Role（时间为回查值）。 */
    @Test
    void updateChangesOnlyRoleName() {
        SysRole existing = savedRole(37L, "EDITOR", "旧名称");
        existing.setStatus(0);
        SysRole reloaded = savedRole(37L, "EDITOR", "新名称");
        reloaded.setStatus(0);
        when(sysRoleMapper.selectById(37L)).thenReturn(existing, reloaded);

        RoleView view = service.update(37L, new RoleUpdateRequest("新名称"));

        assertThat(view.roleCode()).isEqualTo("EDITOR");
        assertThat(view.roleName()).isEqualTo("新名称");
        assertThat(view.status()).isEqualTo(0);

        ArgumentCaptor<SysRole> captor = ArgumentCaptor.forClass(SysRole.class);
        verify(sysRoleMapper).updateById(captor.capture());
        // 评审 P1：最小更新实体——SET 子句仅 role_name，不回写并发读到的旧编码/状态
        assertThat(captor.getValue().getId()).isEqualTo(37L);
        assertThat(captor.getValue().getRoleName()).isEqualTo("新名称");
        assertThat(captor.getValue().getRoleCode()).isNull();
        assertThat(captor.getValue().getStatus()).isNull();
    }

    /** 修改：角色不存在 404，不执行写库。 */
    @Test
    void updateNonexistentRoleReturns404() {
        when(sysRoleMapper.selectById(999L)).thenReturn(null);

        assertThatThrownBy(() -> service.update(999L, new RoleUpdateRequest("新名称")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getResultCode())
                .isEqualTo(ResultCode.NOT_FOUND);

        verify(sysRoleMapper, never()).updateById(any(SysRole.class));
    }

    /** 修改：roleName 去除首尾空白后写库，响应返回规范化值。 */
    @Test
    void updateTrimsRoleNameBeforeWrite() {
        when(sysRoleMapper.selectById(37L)).thenReturn(savedRole(37L, "EDITOR", "旧名称"),
                savedRole(37L, "EDITOR", "新名称"));

        service.update(37L, new RoleUpdateRequest("  新名称\t"));

        ArgumentCaptor<SysRole> captor = ArgumentCaptor.forClass(SysRole.class);
        verify(sysRoleMapper).updateById(captor.capture());
        assertThat(captor.getValue().getRoleName()).isEqualTo("新名称");
    }

    /** 修改：协作约定 8.1 先校验原始长度——带空格的 101 码点输入拒绝，即使清理后只有 99。 */
    @Test
    void updateRawLengthIsCheckedBeforeTrimming() {
        String raw = " " + "名".repeat(99) + " ";

        assertThatThrownBy(() -> service.update(37L, new RoleUpdateRequest(raw)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getResultCode())
                .isEqualTo(ResultCode.BAD_REQUEST);

        verify(sysRoleMapper, never()).updateById(any(SysRole.class));
    }

    /**
     * 评审 P2 回归：roleName 仅含 NBSP（U+00A0）——Java 的 isBlank()/strip()
     * 都放行（Java 空白判定不认 NBSP），必须按契约 \S 的 ECMAScript 空白判定
     * 返回 400。判空/裁剪统一委托 {@code ContractWhitespace}，与 HTTP DTO 的
     * {@code @ContractNotBlank} 同源。
     */
    @Test
    void updateNbspOnlyRoleNameReturns400() {
        assertThatThrownBy(() -> service.update(37L, new RoleUpdateRequest("\u00A0")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getResultCode())
                .isEqualTo(ResultCode.BAD_REQUEST);

        verify(sysRoleMapper, never()).selectById(any(Long.class));
        verify(sysRoleMapper, never()).updateById(any(SysRole.class));
    }

    /** 评审 P2 回归：混合"不可见空白"（空格 + NBSP + 窄 NBSP + BOM）-only 同样拒绝，不存在绕过组合。 */
    @Test
    void updateMixedInvisibleWhitespaceOnlyRoleNameReturns400() {
        assertThatThrownBy(() -> service.update(37L, new RoleUpdateRequest(" \u00A0\u202F\uFEFF\t")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getResultCode())
                .isEqualTo(ResultCode.BAD_REQUEST);

        verify(sysRoleMapper, never()).updateById(any(SysRole.class));
    }

    /** 评审 P2 回归：NBSP 不在首尾时（如 "A\u00A0B"）契约 \S 匹配，放行并原样写库。 */
    @Test
    void updateEmbeddedNbspIsAcceptedAsNonBlank() {
        when(sysRoleMapper.selectById(37L)).thenReturn(savedRole(37L, "EDITOR", "旧名称"),
                savedRole(37L, "EDITOR", "A\u00A0B"));

        service.update(37L, new RoleUpdateRequest("A\u00A0B"));

        ArgumentCaptor<SysRole> captor = ArgumentCaptor.forClass(SysRole.class);
        verify(sysRoleMapper).updateById(captor.capture());
        assertThat(captor.getValue().getRoleName()).isEqualTo("A\u00A0B");
    }

    /**
     * 评审 P2 回归：首尾 NBSP 参与裁剪——{@code "\u00A0编辑\u00A0"} 写库为
     * "编辑"（裁剪与判空同一套契约规则，协作约定 8.1 "去首尾空白"），
     * 名称内部的空白（含 NBSP）原样保留。
     */
    @Test
    void updateTrimsContractWhitespaceIncludingNbsp() {
        when(sysRoleMapper.selectById(37L)).thenReturn(savedRole(37L, "EDITOR", "旧名称"),
                savedRole(37L, "EDITOR", "编辑"));

        service.update(37L, new RoleUpdateRequest("\u00A0编辑\u00A0"));

        ArgumentCaptor<SysRole> captor = ArgumentCaptor.forClass(SysRole.class);
        verify(sysRoleMapper).updateById(captor.capture());
        assertThat(captor.getValue().getRoleName()).isEqualTo("编辑");
    }

    /** 评审 P2 回归：创建同样裁剪首尾 NBSP——{@code "\u00A0CODE\u00A0"} 写库为 CODE。 */
    @Test
    void createTrimsContractWhitespaceIncludingNbsp() {
        doAnswer(invocation -> {
            SysRole entity = invocation.getArgument(0);
            entity.setId(81L);
            return 1;
        }).when(sysRoleMapper).insert(any(SysRole.class));
        when(sysRoleMapper.selectById(81L)).thenReturn(savedRole(81L, "CODE", "名称"));

        service.create(new RoleCreateRequest("\u00A0CODE\u00A0", " 名称 "));

        ArgumentCaptor<SysRole> captor = ArgumentCaptor.forClass(SysRole.class);
        verify(sysRoleMapper).insert(captor.capture());
        assertThat(captor.getValue().getRoleCode()).isEqualTo("CODE");
        assertThat(captor.getValue().getRoleName()).isEqualTo("名称");
    }

    /**
     * 评审 P2 回归：U+001C（信息分隔控制符）按 ECMAScript {@code \s} 是<b>非空白</b>，
     * 首尾出现也不裁剪——{@code Character.isWhitespace} 会把它错裁掉导致
     * {@code "\u001C编辑\u001C"} 被存成"编辑"。
     */
    @Test
    void updateDoesNotTrimInformationSeparators() {
        when(sysRoleMapper.selectById(37L)).thenReturn(savedRole(37L, "EDITOR", "旧名称"),
                savedRole(37L, "EDITOR", "编辑"));

        service.update(37L, new RoleUpdateRequest("\u001C编辑\u001C"));

        ArgumentCaptor<SysRole> captor = ArgumentCaptor.forClass(SysRole.class);
        verify(sysRoleMapper).updateById(captor.capture());
        assertThat(captor.getValue().getRoleName()).isEqualTo("\u001C编辑\u001C");
    }

    /** 评审 P2 回归：仅含 U+001C 的输入按契约 \S 是非空白，放行并原样写库（不误拒）。 */
    @Test
    void informationSeparatorOnlyRoleNameIsNonBlankPerContract() {
        when(sysRoleMapper.selectById(37L)).thenReturn(savedRole(37L, "EDITOR", "旧名称"),
                savedRole(37L, "EDITOR", "\u001C"));

        service.update(37L, new RoleUpdateRequest("\u001C"));

        ArgumentCaptor<SysRole> captor = ArgumentCaptor.forClass(SysRole.class);
        verify(sysRoleMapper).updateById(captor.capture());
        assertThat(captor.getValue().getRoleName()).isEqualTo("\u001C");
    }

    /** 评审 P2 回归：U+000B（垂直制表符）在 ECMAScript \s 中是空白，首尾仍要裁剪。 */
    @Test
    void updateTrimsVerticalTabPerContract() {
        when(sysRoleMapper.selectById(37L)).thenReturn(savedRole(37L, "EDITOR", "旧名称"),
                savedRole(37L, "EDITOR", "编辑"));

        service.update(37L, new RoleUpdateRequest("\u000B编辑\u000B"));

        ArgumentCaptor<SysRole> captor = ArgumentCaptor.forClass(SysRole.class);
        verify(sysRoleMapper).updateById(captor.capture());
        assertThat(captor.getValue().getRoleName()).isEqualTo("编辑");
    }

    /** 评审 P2 回归：创建入口同样按契约空白判定——roleCode/roleName 仅含 NBSP → 400。 */
    @Test
    void createNbspOnlyFieldsReturn400() {
        assertThatThrownBy(() -> service.create(new RoleCreateRequest("\u00A0", "名称")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getResultCode())
                .isEqualTo(ResultCode.BAD_REQUEST);

        assertThatThrownBy(() -> service.create(new RoleCreateRequest("CODE", "\u00A0")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getResultCode())
                .isEqualTo(ResultCode.BAD_REQUEST);

        verify(sysRoleMapper, never()).insert(any(SysRole.class));
    }

    // ---------- 启停角色（PUT /{id}/status） ----------

    /** 启停成功：条件更新只写 status，编码与名称不动，不删除关联。 */
    @Test
    void setStatusChangesOnlyStatus() {
        SysRole existing = savedRole(37L, "EDITOR", "编辑");
        when(sysRoleMapper.selectById(37L)).thenReturn(existing);
        // mock 默认 update 返回 0（幂等分支），必须 stub 为命中 1 行以走实际变更路径
        when(sysRoleMapper.update(any(SysRole.class), any(Wrapper.class))).thenReturn(1);

        service.setStatus(37L, new RoleStatusRequest(0));

        ArgumentCaptor<SysRole> captor = ArgumentCaptor.forClass(SysRole.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<SysRole>> wrapperCaptor =
                ArgumentCaptor.forClass((Class<Wrapper<SysRole>>) (Class<?>) Wrapper.class);
        verify(sysRoleMapper).update(captor.capture(), wrapperCaptor.capture());
        // 评审 P1：最小更新实体——SET 子句仅 status，不回写并发读到的旧编码/名称
        assertThat(captor.getValue().getId()).isNull();
        assertThat(captor.getValue().getStatus()).isEqualTo(0);
        assertThat(captor.getValue().getRoleCode()).isNull();
        assertThat(captor.getValue().getRoleName()).isNull();
        // 评审 P2：条件更新带 status<>?，并发同目标启停请求跳过重复写入
        String sqlSegment = wrapperCaptor.getValue().getSqlSegment();
        assertThat(sqlSegment).contains("id =").contains("status <>");
        // 评审 P2：状态实际变更才进入审计锚点
        verify(roleWriteAuditMarker).roleStatusChanged();
    }

    /** 契约：重复设置同一状态幂等返回 200，不重复写库、不记录审计（无状态变更）。 */
    @Test
    void settingSameStatusIsIdempotentAndSkipsWrite() {
        when(sysRoleMapper.selectById(37L)).thenReturn(savedRole(37L, "EDITOR", "编辑"));

        service.setStatus(37L, new RoleStatusRequest(1));

        verify(sysRoleMapper, never()).updateById(any(SysRole.class));
        verify(sysRoleMapper, never()).update(any(SysRole.class), any(Wrapper.class));
        // 评审 P2：无状态变更路径不进入审计——审计失败不应拖垮本应 200 的幂等请求
        verify(roleWriteAuditMarker, never()).roleStatusChanged();
    }

    /**
     * 评审 P2：并发同目标启停——另一请求已把状态改到目标值，条件更新
     * {@code WHERE status<>?} 影响 0 行，服务按幂等返回 200，且不进入审计
     * （与同状态路径一致：无状态变更即无审计）。
     */
    @Test
    void setStatusConditionalUpdateWithZeroAffectedRowsStillReturns200() {
        when(sysRoleMapper.selectById(37L)).thenReturn(savedRole(37L, "EDITOR", "编辑"));

        service.setStatus(37L, new RoleStatusRequest(0));

        verify(sysRoleMapper).update(any(SysRole.class), any(Wrapper.class));
        verify(roleWriteAuditMarker, never()).roleStatusChanged();
    }

    /** 启停：角色不存在 404。 */
    @Test
    void setStatusNonexistentRoleReturns404() {
        when(sysRoleMapper.selectById(999L)).thenReturn(null);

        assertThatThrownBy(() -> service.setStatus(999L, new RoleStatusRequest(0)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getResultCode())
                .isEqualTo(ResultCode.NOT_FOUND);

        verify(sysRoleMapper, never()).updateById(any(SysRole.class));
        verify(sysRoleMapper, never()).update(any(SysRole.class), any(Wrapper.class));
        verify(roleWriteAuditMarker, never()).roleStatusChanged();
    }

    /** 启停：status 非 0/1（如 2）返回 400，不进入查询。 */
    @Test
    void setStatusBeyondEnumReturns400() {
        assertThatThrownBy(() -> service.setStatus(37L, new RoleStatusRequest(2)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getResultCode())
                .isEqualTo(ResultCode.BAD_REQUEST);

        verify(sysRoleMapper, never()).selectById(any(Long.class));
    }
}
