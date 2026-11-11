package com.xinghe.cms.content.service;

import com.xinghe.cms.common.contract.ArticleSnapshot;
import com.xinghe.cms.common.enums.ArticleAction;

/** 提供审核历史的读取范围检查及文章保存、删除的事务内行锁。 */
public interface ArticleAccessService {

    ArticleSnapshot requireReadable(Long articleId);

    ArticleSnapshot lockEditable(Long articleId, int expectedVersion, ArticleAction action);
}
