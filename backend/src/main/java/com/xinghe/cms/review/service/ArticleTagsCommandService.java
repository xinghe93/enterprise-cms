package com.xinghe.cms.review.service;

import com.xinghe.cms.review.dto.ArticleTagsUpdateRequest;
import com.xinghe.cms.review.vo.ArticleTagsVO;

/** 文章标签绑定命令入口（D 模块，PR 描述中的 {@code PUT /api/admin/articles/{id}/tags}）。 */
public interface ArticleTagsCommandService {

    /**
     * 替换文章标签。
     *
     * @param articleId 文章 ID，必须为正数
     * @param request   版本号与标签 ID 列表
     * @return 绑定后的文章状态与升序标签 ID
     */
    ArticleTagsVO update(long articleId, ArticleTagsUpdateRequest request);
}
