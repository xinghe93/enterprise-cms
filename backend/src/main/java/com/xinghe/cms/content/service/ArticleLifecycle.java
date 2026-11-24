package com.xinghe.cms.content.service;

import com.xinghe.cms.common.contract.ArticleSnapshot;
import com.xinghe.cms.common.contract.ArticleState;
import com.xinghe.cms.common.contract.AuthorizationSnapshot;
import com.xinghe.cms.common.enums.ArticleAction;
import com.xinghe.cms.common.exception.BusinessException;
import com.xinghe.cms.common.response.ResultCode;
import com.xinghe.cms.content.mapper.ArticleMapper;
import com.xinghe.cms.permission.service.AuthorizationQueryService;
import com.xinghe.cms.security.CurrentUserProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ArticleLifecycle implements ArticleAccessService, ArticleWorkflowService {

    private final ArticleMapper mapper;
    private final CurrentUserProvider users;
    private final AuthorizationQueryService permissions;

    public ArticleLifecycle(ArticleMapper mapper, CurrentUserProvider users, AuthorizationQueryService permissions) {
        this.mapper = mapper;
        this.users = users;
        this.permissions = permissions;
    }

    @Override
    @Transactional(readOnly = true)
    public ArticleSnapshot requireReadable(Long articleId) {
        long userId = users.requireCurrentUser().userId();
        AuthorizationSnapshot authorization = permissions.getForUser(userId);
        ArticleSnapshot article = requireArticle(mapper.findSnapshot(articleId));
        if (!ArticlePolicy.readScope(authorization).allows(userId, article.authorId(), article.status())) {
            throw new BusinessException(ResultCode.FORBIDDEN);
        }
        return article;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public ArticleSnapshot lockEditable(Long articleId, int expectedVersion, ArticleAction action) {
        if (action != ArticleAction.SAVE && action != ArticleAction.DELETE) {
            throw new IllegalArgumentException("仅保存和删除可使用 lockEditable");
        }
        return lockForAction(articleId, expectedVersion, action);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public ArticleState transition(Long articleId, int expectedVersion, ArticleAction action) {
        if (action == ArticleAction.SAVE || action == ArticleAction.DELETE) {
            throw new IllegalArgumentException("保存和删除由文章草稿服务处理");
        }
        ArticleSnapshot article = lockForAction(articleId, expectedVersion, action);
        if (mapper.changeState(article, action.targetStatus(article.status())) != 1) {
            requireArticle(mapper.lockSnapshot(articleId));
            throw new BusinessException(ResultCode.CONFLICT, "文章状态或版本已变化，请刷新后重试");
        }
        ArticleSnapshot updated = requireArticle(mapper.findSnapshot(articleId));
        return new ArticleState(updated.id(), updated.status(), updated.version(), updated.publishedAt());
    }

    private ArticleSnapshot lockForAction(Long articleId, int expectedVersion, ArticleAction action) {
        long userId = users.requireCurrentUser().userId();
        AuthorizationSnapshot authorization = permissions.getForUser(userId);
        ArticlePolicy.requirePermission(authorization, action);
        if (expectedVersion < 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "文章版本不能为负数");
        }

        ArticleSnapshot article = requireArticle(mapper.lockSnapshot(articleId));
        ArticlePolicy.requireWriteScope(userId, article.authorId(), authorization, action);
        if (article.version() != expectedVersion || !action.allows(article.status())) {
            throw new BusinessException(ResultCode.CONFLICT, "文章状态或版本已变化，请刷新后重试");
        }
        if (article.version() == Integer.MAX_VALUE) {
            throw new BusinessException(ResultCode.CONFLICT, "文章版本已达到上限");
        }
        return article;
    }

    private static ArticleSnapshot requireArticle(ArticleSnapshot article) {
        if (article == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "文章不存在");
        }
        return article;
    }
}
