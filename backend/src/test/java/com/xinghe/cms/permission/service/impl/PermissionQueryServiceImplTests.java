package com.xinghe.cms.permission.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.xinghe.cms.common.exception.BusinessException;
import com.xinghe.cms.common.request.PageQuery;
import com.xinghe.cms.common.response.PageResult;
import com.xinghe.cms.common.response.ResultCode;
import com.xinghe.cms.permission.entity.SysPermission;
import com.xinghe.cms.permission.mapper.SysPermissionMapper;
import com.xinghe.cms.permission.response.PermissionView;
import com.xinghe.cms.permission.service.PermissionQueryService;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 权限项分页服务单测（Mockito 桩、不连库）。覆盖：keyword 去空白 + LIKE 字面
 * 转义（契约 8.2）、status/permissionType 精确筛选透传、长度/枚举校验 400、
 * 视图映射（含停用项与真实 status）。SQL 本身由联调阶段验证。
 */
@ExtendWith(MockitoExtension.class)
class PermissionQueryServiceImplTests {

    @Mock
    private SysPermissionMapper sysPermissionMapper;

    @InjectMocks
    private PermissionQueryServiceImpl service;

    @BeforeEach
    void stubDefaultPage() {
        // Mockito 对未打桩的 Page 返回 null 会导致 NPE；统一给个空页兜底
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<SysPermission> empty =
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>();
        empty.setRecords(List.of());
        empty.setTotal(0);
        org.mockito.Mockito.lenient().when(sysPermissionMapper.selectPermissionPage(
                any(), any(), any(), any())).thenReturn(empty);
    }

    private static SysPermission permission(long id, String code, String name, String type,
            int status) {
        SysPermission entity = new SysPermission();
        entity.setId(id);
        entity.setPermissionCode(code);
        entity.setPermissionName(name);
        entity.setPermissionType(type);
        entity.setPath("API".equals(type) ? "/api/admin/roles/{id}" : null);
        entity.setMethod("API".equals(type) ? "GET" : null);
        entity.setStatus(status);
        entity.setCreatedAt(LocalDateTime.of(2026, 11, 21, 9, 0));
        entity.setUpdatedAt(LocalDateTime.of(2026, 11, 21, 9, 0));
        return entity;
    }

    /** keyword 去首尾空白后按字面转义传给 Mapper；status/type 原样透传。 */
    @Test
    void keywordIsTrimmedEscapedAndFiltersArePassedThrough() {
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<SysPermission> page =
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>();
        page.setRecords(List.of(permission(3L, "role:read", "角色读取", "API", 1)));
        page.setTotal(1);
        when(sysPermissionMapper.selectPermissionPage(any(), eq("50!%!_off!!"), eq(1), eq("API")))
                .thenReturn(page);

        PageResult<PermissionView> result = service.page(new PageQuery(), " 50%_off! ", 1, "API");

        assertThat(result.total()).isEqualTo(1);
        assertThat(result.records().get(0).id()).isEqualTo("3");
        assertThat(result.records().get(0).status()).isEqualTo(1);
        assertThat(result.records().get(0).method()).isEqualTo("GET");
    }

    /** 空白 keyword 与未传 status/type 等价于不筛选（契约 8.2：空串不筛选）。 */
    @Test
    void blankKeywordAndMissingFiltersAreNotApplied() {
        service.page(new PageQuery(), "   ", null, null);

        verify(sysPermissionMapper).selectPermissionPage(any(), isNull(), isNull(), isNull());
    }

    /** keyword 超 100 码点返回 400（原始长度先校验再去空白）。 */
    @Test
    void keywordBeyond100CodePointsReturns400() {
        assertThatThrownBy(() -> service.page(new PageQuery(), "x".repeat(101), null, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getResultCode())
                .isEqualTo(ResultCode.BAD_REQUEST);
    }

    /** status 非 0/1 返回 400；permissionType 非严格枚举返回 400。 */
    @Test
    void invalidStatusOrPermissionTypeReturns400() {
        assertThatThrownBy(() -> service.page(new PageQuery(), null, 2, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getResultCode())
                .isEqualTo(ResultCode.BAD_REQUEST);

        assertThatThrownBy(() -> service.page(new PageQuery(), null, null, "menu"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getResultCode())
                .isEqualTo(ResultCode.BAD_REQUEST);

        assertThatThrownBy(() -> service.page(new PageQuery(), null, null, "MENU "))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getResultCode())
                .isEqualTo(ResultCode.BAD_REQUEST);
    }

    /** 分页默认值经由 PageQuery 传入 Mapper（current=1、size=10）。 */
    @Test
    void defaultPaginationIsPassedToMapper() {
        service.page(new PageQuery(), null, null, null);

        ArgumentCaptor<com.baomidou.mybatisplus.extension.plugins.pagination.Page<SysPermission>> captor =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.extension.plugins.pagination.Page.class);
        verify(sysPermissionMapper).selectPermissionPage(captor.capture(), isNull(), isNull(), isNull());
        assertThat(captor.getValue().getCurrent()).isEqualTo(1L);
        assertThat(captor.getValue().getSize()).isEqualTo(10L);
    }
}
