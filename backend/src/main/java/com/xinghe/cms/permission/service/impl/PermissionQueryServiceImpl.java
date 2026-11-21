package com.xinghe.cms.permission.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.xinghe.cms.common.exception.BusinessException;
import com.xinghe.cms.common.request.PageQuery;
import com.xinghe.cms.common.response.PageResult;
import com.xinghe.cms.common.response.ResultCode;
import com.xinghe.cms.permission.entity.SysPermission;
import com.xinghe.cms.permission.mapper.SysPermissionMapper;
import com.xinghe.cms.permission.response.PermissionView;
import com.xinghe.cms.permission.service.PermissionQueryService;
import com.xinghe.cms.permission.support.ContractLike;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 权限项只读查询实现。筛选与校验规则对齐 OpenAPI
 * {@code GET /api/admin/permissions}：keyword 最长 100、LIKE 通配符字面匹配；
 * status 仅允许 0/1；permissionType 严格匹配 MENU/BUTTON/API。
 */
@Service
@Transactional(readOnly = true)
public class PermissionQueryServiceImpl implements PermissionQueryService {

    private static final int MAX_KEYWORD_LENGTH = 100;

    /** OpenAPI permissionType 查询参数的合法枚举（严格匹配）。 */
    static final Set<String> PERMISSION_TYPES = Set.of("MENU", "BUTTON", "API");

    private final SysPermissionMapper sysPermissionMapper;

    public PermissionQueryServiceImpl(SysPermissionMapper sysPermissionMapper) {
        this.sysPermissionMapper = sysPermissionMapper;
    }

    @Override
    public PageResult<PermissionView> page(PageQuery query, String keyword, Integer status,
            String permissionType) {
        Objects.requireNonNull(query, "分页参数不能为空");
        if (status != null && status != 0 && status != 1) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "状态只允许 0（停用）或 1（启用）");
        }
        if (permissionType != null && !PERMISSION_TYPES.contains(permissionType)) {
            throw new BusinessException(ResultCode.BAD_REQUEST,
                    "权限类型只允许 MENU、BUTTON 或 API");
        }
        // 契约 8.x + OpenAPI maxLength：长度按 Unicode 码点统计（maxLength 语义为字符数，
        // String.length() 统计 UTF-16 代码单元会把增补字符如 emoji 重复计数），
        // 且原始长度先校验再去首尾空白——避免用空白填充绕过上限
        if (keyword != null && keyword.codePointCount(0, keyword.length()) > MAX_KEYWORD_LENGTH) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "关键词最长 100 个字符");
        }
        String trimmed = keyword == null ? null : keyword.strip();
        if (trimmed != null && trimmed.isEmpty()) {
            trimmed = null;
        }
        String escaped = trimmed == null ? null : ContractLike.escape(trimmed);

        Page<SysPermission> result = sysPermissionMapper.selectPermissionPage(
                query.toPage(), escaped, status, permissionType);
        List<PermissionView> records = result.getRecords().stream().map(PermissionView::from).toList();
        return new PageResult<>(records, result.getTotal(), result.getCurrent(), result.getSize());
    }
}
