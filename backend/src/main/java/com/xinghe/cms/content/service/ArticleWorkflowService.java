package com.xinghe.cms.content.service;

import com.xinghe.cms.common.contract.ArticleState;
import com.xinghe.cms.common.enums.ArticleAction;

/** 由 C/D 编排方在同一业务事务内调用，成功更新一次文章版本。 */
public interface ArticleWorkflowService {

    ArticleState transition(Long articleId, int expectedVersion, ArticleAction action);
}
