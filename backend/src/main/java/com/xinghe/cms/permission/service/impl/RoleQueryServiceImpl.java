package com.xinghe.cms.permission.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.xinghe.cms.common.exception.BusinessException;
import com.xinghe.cms.common.request.PageQuery;
import com.xinghe.cms.common.response.PageResult;
import com.xinghe.cms.common.response.ResultCode;
import com.xinghe.cms.permission.entity.SysRole;
import com.xinghe.cms.permission.mapper.SysRoleMapper;
import com.xinghe.cms.permission.response.RoleView;
import com.xinghe.cms.permission.service.RoleQueryService;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 角色只读查询实现。筛选与校验规则对齐 OpenAPI {@code GET /api/admin/roles}：
 * keyword 最长 100、LIKE 通配符字面匹配；status 仅允许 0/1。
 */
@Service
@Transactional(readOnly = true)
public class RoleQueryServiceImpl implements RoleQueryService {

    private static final int MAX_KEYWORD_LENGTH = 100;

    private final SysRoleMapper sysRoleMapper;

    public RoleQueryServiceImpl(SysRoleMapper sysRoleMapper) {
        this.sysRoleMapper = sysRoleMapper;
    }

    @Override
    public PageResult<RoleView> page(PageQuery query, String keyword, Integer status) {
        Objects.requireNonNull(query, "分页参数不能为空");
        if (status != null && status != 0 && status != 1) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "状态只允许 0（停用）或 1（启用）");
        }
        String trimmed = keyword == null ? null : keyword.strip();
        if (trimmed != null && trimmed.isEmpty()) {
            trimmed = null;
        }
        if (trimmed != null && trimmed.length() > MAX_KEYWORD_LENGTH) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "关键词最长 100 个字符");
        }
        String escaped = trimmed == null ? null : escapeLike(trimmed);

        Page<SysRole> result = sysRoleMapper.selectRolePage(query.toPage(), escaped, status);
        List<RoleView> records = result.getRecords().stream().map(RoleView::from).toList();
        return new PageResult<>(records, result.getTotal(), result.getCurrent(), result.getSize());
    }

    @Override
    public RoleView getById(long id) {
        SysRole role = sysRoleMapper.selectById(id);
        if (role == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "角色不存在");
        }
        return RoleView.from(role);
    }

    /**
     * LIKE 通配符按字面转义：{@code !} 为转义字符，与 Mapper 中
     * {@code ESCAPE '!'} 对应；{@code %}、{@code _}、{@code !} 一律前置
     * 转义符，其余字符原样保留。
     */
    static String escapeLike(String raw) {
        StringBuilder builder = new StringBuilder(raw.length());
        for (char ch : raw.toCharArray()) {
            if (ch == '!' || ch == '%' || ch == '_') {
                builder.append('!');
            }
            builder.append(ch);
        }
        return builder.toString();
    }
}
