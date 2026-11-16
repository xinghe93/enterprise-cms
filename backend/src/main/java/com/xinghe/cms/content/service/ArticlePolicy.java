package com.xinghe.cms.content.service;

import com.xinghe.cms.common.contract.AuthorizationSnapshot;
import com.xinghe.cms.common.enums.ArticleAction;
import com.xinghe.cms.common.enums.ArticleStatus;
import com.xinghe.cms.common.exception.BusinessException;
import com.xinghe.cms.common.response.ResultCode;

import java.util.Set;

final class ArticlePolicy {

    private static final Set<String> REVIEW_PERMISSIONS = Set.of(
            "article:review-list", ArticleAction.APPROVE.permission(), ArticleAction.REJECT.permission(),
            ArticleAction.PUBLISH.permission(), ArticleAction.OFFLINE.permission(),
            ArticleAction.BATCH_PUBLISH.permission(), ArticleAction.BATCH_OFFLINE.permission());

    enum ReadScope {
        OWN, REVIEW, ALL;

        boolean allows(long userId, long authorId, ArticleStatus status) {
            return this == ALL || userId == authorId || (this == REVIEW && status != ArticleStatus.DRAFT);
        }
    }

    private ArticlePolicy() {
    }

    static ReadScope readScope(AuthorizationSnapshot authorization) {
        if (authorization.enabledRoleCodes().contains("ADMIN")) {
            return ReadScope.ALL;
        }
        if (authorization.enabledRoleCodes().contains("REVIEWER")
                || authorization.permissionCodes().stream().anyMatch(REVIEW_PERMISSIONS::contains)) {
            return ReadScope.REVIEW;
        }
        return ReadScope.OWN;
    }

    static void requirePermission(AuthorizationSnapshot authorization, ArticleAction action) {
        requirePermission(authorization, action.permission());
    }

    static void requirePermission(AuthorizationSnapshot authorization, String permission) {
        if (!authorization.permissionCodes().contains(permission)) {
            throw new BusinessException(ResultCode.FORBIDDEN);
        }
    }

    static void requireWriteScope(long userId, long authorId, AuthorizationSnapshot authorization,
            ArticleAction action) {
        boolean allowed = switch (action) {
            case APPROVE, REJECT -> userId != authorId;
            case PUBLISH, OFFLINE, BATCH_PUBLISH, BATCH_OFFLINE -> true;
            case SAVE, DELETE, SUBMIT, BIND_TAGS ->
                    userId == authorId || authorization.enabledRoleCodes().contains("ADMIN");
        };
        if (!allowed) {
            throw new BusinessException(ResultCode.FORBIDDEN);
        }
    }
}
