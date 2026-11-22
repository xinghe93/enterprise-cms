package com.xinghe.cms.permission.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.xinghe.cms.common.exception.BusinessException;
import com.xinghe.cms.common.request.PageQuery;
import com.xinghe.cms.common.response.PageResult;
import com.xinghe.cms.common.response.ResultCode;
import com.xinghe.cms.permission.entity.SysRole;
import com.xinghe.cms.permission.mapper.SysRoleMapper;
import com.xinghe.cms.permission.response.RoleView;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link RoleQueryServiceImpl} 行为单测：验证筛选校验、LIKE 转义、
 * 分页映射与 404 边界。SQL 正确性由 Mapper 的 @Select 保证，集成验证在联调阶段进行。
 */
@ExtendWith(MockitoExtension.class)
class RoleQueryServiceImplTests {

    @Mock
    private SysRoleMapper sysRoleMapper;

    @InjectMocks
    private RoleQueryServiceImpl service;

    private static SysRole role(long id, String code, String name, int status) {
        SysRole entity = new SysRole();
        entity.setId(id);
        entity.setRoleCode(code);
        entity.setRoleName(name);
        entity.setStatus(status);
        entity.setCreatedAt(LocalDateTime.of(2026, 11, 10, 9, 30, 0, 0));
        entity.setUpdatedAt(LocalDateTime.of(2026, 11, 10, 9, 30, 0, 0));
        return entity;
    }

    @Test
    void escapesLikeWildcardsLitterally() {
        // %、_、! 前置转义符 !，其余字符原样保留
        assertThat(RoleQueryServiceImpl.escapeLike("50%_off!")).isEqualTo("50!%!_off!!");
        assertThat(RoleQueryServiceImpl.escapeLike("plain")).isEqualTo("plain");
    }

    @Test
    void passesEscapedKeywordAndStatusToMapper() {
        Page<SysRole> empty = new Page<>(1, 10);
        when(sysRoleMapper.selectRolePage(any(), eq("EDITOR"), eq(1))).thenReturn(empty);

        service.page(new PageQuery(), "  EDITOR  ", 1);

        ArgumentCaptor<String> keywordCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Integer> statusCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(sysRoleMapper).selectRolePage(any(Page.class), keywordCaptor.capture(), statusCaptor.capture());
        // 关键词去首尾空白
        assertThat(keywordCaptor.getValue()).isEqualTo("EDITOR");
        assertThat(statusCaptor.getValue()).isEqualTo(1);
    }

    @Test
    void treatsBlankKeywordAsNoFilter() {
        Page<SysRole> empty = new Page<>(1, 10);
        when(sysRoleMapper.selectRolePage(any(), isNull(), isNull())).thenReturn(empty);

        service.page(new PageQuery(), "   ", null);

        verify(sysRoleMapper).selectRolePage(any(Page.class), isNull(), isNull());
    }

    /**
     * 评审 P2 回归：keyword 空白按契约 ECMAScript \s 处理——纯 NBSP（U+00A0）
     * 是契约空白，须视为"空关键词不筛选"；String.strip 的
     * Character.isWhitespace 不认 NBSP，旧实现会把它当字面关键词送进 LIKE。
     */
    @Test
    void nbspOnlyKeywordIsTreatedAsNoFilter() {
        Page<SysRole> empty = new Page<>(1, 10);
        when(sysRoleMapper.selectRolePage(any(), isNull(), isNull())).thenReturn(empty);

        service.page(new PageQuery(), "\u00A0", null);

        verify(sysRoleMapper).selectRolePage(any(Page.class), isNull(), isNull());
    }

    /** 评审 P2 回归：NBSP 包夹的关键词被契约空白规则去除首尾后进入 LIKE。 */
    @Test
    void nbspPaddedKeywordIsTrimmedBeforeEscaping() {
        Page<SysRole> empty = new Page<>(1, 10);
        when(sysRoleMapper.selectRolePage(any(), eq("EDITOR"), isNull())).thenReturn(empty);

        service.page(new PageQuery(), "\u00A0EDITOR\u00A0", null);

        ArgumentCaptor<String> keywordCaptor = ArgumentCaptor.forClass(String.class);
        verify(sysRoleMapper).selectRolePage(any(Page.class), keywordCaptor.capture(), isNull());
        assertThat(keywordCaptor.getValue()).isEqualTo("EDITOR");
    }

    @Test
    void rejectsStatusOtherThanZeroOrOne() {
        assertThatThrownBy(() -> service.page(new PageQuery(), null, 2))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getResultCode())
                .isEqualTo(ResultCode.BAD_REQUEST);

        verifyNoInteractions(sysRoleMapper);
    }

    @Test
    void rejectsOverlongKeyword() {
        assertThatThrownBy(() -> service.page(new PageQuery(), "k".repeat(101), null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getResultCode())
                .isEqualTo(ResultCode.BAD_REQUEST);

        verifyNoInteractions(sysRoleMapper);
    }

    /** 契约 8.x：原始长度先校验再去空白，空白填充不得绕过上限（PR #23 P3）。 */
    @Test
    void rejectsKeywordPaddedToOverlongByWhitespace() {
        assertThatThrownBy(() -> service.page(new PageQuery(), " ".repeat(100) + "x", null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getResultCode())
                .isEqualTo(ResultCode.BAD_REQUEST);

        verifyNoInteractions(sysRoleMapper);
    }

    /** 复审 P3：maxLength 语义为字符数，100 个 emoji（200 个 UTF-16 代码单元）应放行。 */
    @Test
    void acceptsKeywordOfHundredSupplementaryCharacters() {
        String keyword = "😀".repeat(100);
        // 证明长度不是按 UTF-16 代码单元统计的，否则这里会是 200 > 100 被误拒
        assertThat(keyword.length()).isEqualTo(200);

        Page<SysRole> empty = new Page<>(1, 10);
        when(sysRoleMapper.selectRolePage(any(), eq(keyword), isNull())).thenReturn(empty);

        assertThatCode(() -> service.page(new PageQuery(), keyword, null)).doesNotThrowAnyException();
    }

    @Test
    void mapsPageResultWithSerializedId() {
        Page<SysRole> page = new Page<>(2, 10);
        page.setRecords(List.of(role(37, "EDITOR", "编辑", 1)));
        page.setTotal(42);
        when(sysRoleMapper.selectRolePage(any(), isNull(), isNull())).thenReturn(page);

        PageResult<RoleView> result = service.page(query(2, 10), null, null);

        // 分页元数据透传；ID 按契约输出为十进制字符串
        assertThat(result.total()).isEqualTo(42);
        assertThat(result.current()).isEqualTo(2);
        assertThat(result.size()).isEqualTo(10);
        assertThat(result.records()).hasSize(1);
        RoleView view = result.records().get(0);
        assertThat(view.id()).isEqualTo("37");
        assertThat(view.roleCode()).isEqualTo("EDITOR");
        assertThat(view.roleName()).isEqualTo("编辑");
        assertThat(view.status()).isEqualTo(1);
        assertThat(view.createdAt()).isEqualTo(LocalDateTime.of(2026, 11, 10, 9, 30, 0, 0));
    }

    @Test
    void returnsEmptyRecordsWhenPageHasNoRows() {
        Page<SysRole> page = new Page<>(1, 10);
        page.setRecords(List.of());
        page.setTotal(0);
        when(sysRoleMapper.selectRolePage(any(), isNull(), isNull())).thenReturn(page);

        PageResult<RoleView> result = service.page(new PageQuery(), null, null);

        // 空分页是合法结果：records 为空数组而非 null
        assertThat(result.records()).isEmpty();
        assertThat(result.total()).isZero();
    }

    @Test
    void getByIdReturnsView() {
        when(sysRoleMapper.selectById(37L)).thenReturn(role(37, "ADMIN", "管理员", 1));

        RoleView view = service.getById(37L);

        assertThat(view.id()).isEqualTo("37");
        assertThat(view.roleCode()).isEqualTo("ADMIN");
    }

    @Test
    void getByIdRejectsMissingRoleWith404() {
        when(sysRoleMapper.selectById(99L)).thenReturn(null);

        assertThatThrownBy(() -> service.getById(99L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getResultCode())
                .isEqualTo(ResultCode.NOT_FOUND);
    }

    private static PageQuery query(long current, long size) {
        PageQuery query = new PageQuery();
        query.setCurrent(current);
        query.setSize(size);
        return query;
    }
}
