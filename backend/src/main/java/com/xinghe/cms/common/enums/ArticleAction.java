package com.xinghe.cms.common.enums;

import static com.xinghe.cms.common.enums.ArticleStatus.APPROVED;
import static com.xinghe.cms.common.enums.ArticleStatus.DRAFT;
import static com.xinghe.cms.common.enums.ArticleStatus.PENDING_REVIEW;
import static com.xinghe.cms.common.enums.ArticleStatus.PUBLISHED;
import static com.xinghe.cms.common.enums.ArticleStatus.REJECTED;

public enum ArticleAction {
    SAVE("article:update"),
    DELETE("article:delete"),
    SUBMIT("article:submit"),
    APPROVE("article:approve"),
    REJECT("article:reject"),
    PUBLISH("article:publish"),
    OFFLINE("article:offline"),
    BIND_TAGS("article:bind-tag"),
    BATCH_PUBLISH("article:publish-batch"),
    BATCH_OFFLINE("article:offline-batch");

    private final String permission;

    ArticleAction(String permission) {
        this.permission = permission;
    }

    public String permission() {
        return permission;
    }

    public boolean allows(ArticleStatus status) {
        return switch (this) {
            case SAVE, DELETE, SUBMIT, BIND_TAGS ->
                    status == DRAFT || status == REJECTED || status == ArticleStatus.OFFLINE;
            case APPROVE, REJECT -> status == PENDING_REVIEW;
            case PUBLISH, BATCH_PUBLISH -> status == APPROVED;
            case OFFLINE, BATCH_OFFLINE -> status == PUBLISHED;
        };
    }

    public ArticleStatus targetStatus(ArticleStatus status) {
        return switch (this) {
            case SAVE, BIND_TAGS -> status == ArticleStatus.OFFLINE ? DRAFT : status;
            case DELETE -> status;
            case SUBMIT -> PENDING_REVIEW;
            case APPROVE -> APPROVED;
            case REJECT -> REJECTED;
            case PUBLISH, BATCH_PUBLISH -> PUBLISHED;
            case OFFLINE, BATCH_OFFLINE -> ArticleStatus.OFFLINE;
        };
    }
}
