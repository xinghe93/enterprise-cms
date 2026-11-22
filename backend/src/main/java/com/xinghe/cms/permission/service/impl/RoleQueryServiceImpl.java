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
import com.xinghe.cms.permission.support.ContractWhitespace;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 角色只读查询实现。筛选与校验规则对齐 OpenAPI {@code GET /api/admin/roles}：
 * keyword 最长 100、首尾空白按契约 ECMAScript {@code \s} 语义去除
 * （{@link ContractWhitespace#strip}，与权限分页同一真源）、LIKE 通配符字面
 * 匹配；status 仅允许 0/1。
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
        // 契约 8.x + OpenAPI maxLength：长度按 Unicode 码点统计（maxLength 语义为字符数，
        // String.length() 统计 UTF-16 代码单元会把增补字符如 emoji 重复计数），
        // 且原始长度先校验再去首尾空白——避免用空白填充绕过上限
        if (keyword != null && keyword.codePointCount(0, keyword.length()) > MAX_KEYWORD_LENGTH) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "关键词最长 100 个字符");
        }
        // 契约空白规则（评审 P2）：与权限分页同一真源 ContractWhitespace.strip
        //（契约 ECMAScript \s 语义，认 NBSP 等 Zs 类字符），不能用
        // String.strip——其 Character.isWhitespace 不认 NBSP。null 保持 null
        //（strip 不接受 null），纯空白按契约 8.2"空串不筛选"转为 null
        String trimmed = keyword == null ? null : ContractWhitespace.strip(keyword);
        if (trimmed != null && trimmed.isEmpty()) {
            trimmed = null;
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
     *
     * <p>实现已提取到 {@link ContractLike#escape}（权限分页共用同一真源），
     * 本方法保留委托以维持既有调用与测试引用。</p>
     */
    static String escapeLike(String raw) {
        return com.xinghe.cms.permission.support.ContractLike.escape(raw);
    }
}
