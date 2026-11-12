package com.xinghe.cms.content.service;

import com.xinghe.cms.common.contract.ArticleSnapshot;
import com.xinghe.cms.common.contract.ArticleState;
import com.xinghe.cms.common.contract.AuthorizationSnapshot;
import com.xinghe.cms.common.enums.ArticleAction;
import com.xinghe.cms.common.enums.ArticleStatus;
import com.xinghe.cms.common.exception.BusinessException;
import com.xinghe.cms.common.response.ResultCode;
import com.xinghe.cms.content.dto.ArticleDraft;
import com.xinghe.cms.content.entity.Article;
import com.xinghe.cms.content.mapper.ArticleMapper;
import com.xinghe.cms.permission.service.AuthorizationQueryService;
import com.xinghe.cms.security.CurrentUserProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 文章主表写入。调用方在同一外层事务内处理标签绑定与响应读取。 */
@Service
public class ArticleDraftService {

    private final ArticleMapper mapper;
    private final CategoryService categories;
    private final ArticleAccessService access;
    private final ArticleWorkflowService workflow;
    private final CurrentUserProvider users;
    private final AuthorizationQueryService permissions;

    public ArticleDraftService(ArticleMapper mapper, CategoryService categories, ArticleAccessService access,
            ArticleWorkflowService workflow, CurrentUserProvider users, AuthorizationQueryService permissions) {
        this.mapper = mapper;
        this.categories = categories;
        this.access = access;
        this.workflow = workflow;
        this.users = users;
        this.permissions = permissions;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Long create(ArticleDraft draft) {
        long userId = users.requireCurrentUser().userId();
        AuthorizationSnapshot authorization = permissions.getForUser(userId);
        ArticlePolicy.requirePermission(authorization, "article:create");
        categories.lockAssignable(draft.categoryId());

        Article article = new Article();
        article.setTitle(draft.title());
        article.setSummary(draft.summary());
        article.setContent(draft.content());
        article.setCategoryId(draft.categoryId());
        article.setAuthorId(userId);
        article.setStatus(ArticleStatus.DRAFT);
        article.setVisibility("ALL");
        article.setVersion(0);
        article.setIsDeleted(0);
        mapper.insert(article);
        return article.getId();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void save(Long articleId, int expectedVersion, ArticleDraft draft) {
        ArticleSnapshot article = access.lockEditable(articleId, expectedVersion, ArticleAction.SAVE);
        if (!article.categoryId().equals(draft.categoryId())) {
            categories.lockAssignable(draft.categoryId());
        }
        requireUpdated(mapper.saveDraft(article, ArticleAction.SAVE.targetStatus(article.status()), draft));
    }

    @Transactional
    public void delete(Long articleId, int expectedVersion) {
        ArticleSnapshot article = access.lockEditable(articleId, expectedVersion, ArticleAction.DELETE);
        requireUpdated(mapper.markDeleted(article));
    }

    @Transactional
    public ArticleState submit(Long articleId, int expectedVersion) {
        return workflow.transition(articleId, expectedVersion, ArticleAction.SUBMIT);
    }

    private static void requireUpdated(int updatedRows) {
        if (updatedRows != 1) {
            throw new BusinessException(ResultCode.CONFLICT, "文章状态或版本已变化，请刷新后重试");
        }
    }
}
