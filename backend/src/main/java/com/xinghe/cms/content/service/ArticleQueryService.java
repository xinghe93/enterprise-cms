package com.xinghe.cms.content.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.xinghe.cms.common.contract.AuthorizationSnapshot;
import com.xinghe.cms.common.exception.BusinessException;
import com.xinghe.cms.common.response.PageResult;
import com.xinghe.cms.common.response.ResultCode;
import com.xinghe.cms.content.dto.ArticleQuery;
import com.xinghe.cms.content.mapper.ArticleMapper;
import com.xinghe.cms.content.mapper.row.ArticleRow;
import com.xinghe.cms.permission.service.AuthorizationQueryService;
import com.xinghe.cms.security.CurrentUserProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

/** 批量标签查询在调用方取得当前页文章 ID 后执行。 */
@Service
@Transactional(readOnly = true)
public class ArticleQueryService {

    private final ArticleMapper mapper;
    private final CategoryService categories;
    private final CurrentUserProvider users;
    private final AuthorizationQueryService permissions;

    public ArticleQueryService(ArticleMapper mapper, CategoryService categories,
            CurrentUserProvider users, AuthorizationQueryService permissions) {
        this.mapper = mapper;
        this.categories = categories;
        this.users = users;
        this.permissions = permissions;
    }

    public PageResult<ArticleRow> adminPage(ArticleQuery query) {
        long userId = users.requireCurrentUser().userId();
        AuthorizationSnapshot authorization = permissions.getForUser(userId);
        ArticlePolicy.requirePermission(authorization, "article:list");
        ArticlePolicy.ReadScope scope = ArticlePolicy.readScope(authorization);
        return findPage(query, false, scope == ArticlePolicy.ReadScope.ALL ? null : userId,
                scope == ArticlePolicy.ReadScope.REVIEW);
    }

    public ArticleRow adminDetail(Long id) {
        long userId = users.requireCurrentUser().userId();
        AuthorizationSnapshot authorization = permissions.getForUser(userId);
        ArticlePolicy.requirePermission(authorization, "article:read");
        ArticleRow row = requireDetail(id, false);
        if (!ArticlePolicy.readScope(authorization).allows(userId, row.authorId(), row.status())) {
            throw new BusinessException(ResultCode.FORBIDDEN);
        }
        return row;
    }

    public PageResult<ArticleRow> portalPage(ArticleQuery query) {
        AuthorizationSnapshot authorization = permissions.getForUser(users.requireCurrentUser().userId());
        ArticlePolicy.requirePermission(authorization, "portal:article:list");
        return findPage(query, true, null, false);
    }

    public ArticleRow portalDetail(Long id) {
        AuthorizationSnapshot authorization = permissions.getForUser(users.requireCurrentUser().userId());
        ArticlePolicy.requirePermission(authorization, "portal:article:read");
        return requireDetail(id, true);
    }

    private PageResult<ArticleRow> findPage(ArticleQuery query, boolean portal,
            Long scopeUserId, boolean includeOtherNonDrafts) {
        Page<ArticleRow> page = query.toPage();
        String keyword = escapeKeyword(query.getKeyword());
        Set<Long> categoryIds = query.getCategoryId() == null
                ? null : categories.descendantIds(query.getCategoryId());
        if (categoryIds != null && categoryIds.isEmpty()) {
            return PageResult.from(page);
        }
        return PageResult.from(mapper.findPage(page, portal, query.getAuthorId(), scopeUserId,
                includeOtherNonDrafts, query.getStatus(), categoryIds, query.getTagId(), keyword));
    }

    private ArticleRow requireDetail(Long id, boolean portal) {
        ArticleRow row = mapper.findDetail(id, portal);
        if (row == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "文章不存在");
        }
        return row;
    }

    private static String escapeKeyword(String keyword) {
        if (keyword == null) {
            return null;
        }
        if (keyword.codePointCount(0, keyword.length()) > 255) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "检索关键词最多 255 个字符");
        }
        String trimmed = keyword.strip();
        return trimmed.isEmpty() ? null
                : "%" + trimmed.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
    }
}
