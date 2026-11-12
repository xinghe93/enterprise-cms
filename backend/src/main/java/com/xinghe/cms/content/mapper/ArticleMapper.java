package com.xinghe.cms.content.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.xinghe.cms.common.contract.ArticleSnapshot;
import com.xinghe.cms.common.enums.ArticleStatus;
import com.xinghe.cms.content.dto.ArticleDraft;
import com.xinghe.cms.content.entity.Article;
import com.xinghe.cms.content.mapper.row.ArticleRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Set;

@Mapper
public interface ArticleMapper extends BaseMapper<Article> {

    ArticleSnapshot findSnapshot(@Param("id") Long id);

    ArticleSnapshot lockSnapshot(@Param("id") Long id);

    Page<ArticleRow> findPage(Page<ArticleRow> page, @Param("portal") boolean portal,
            @Param("authorId") Long authorId, @Param("scopeUserId") Long scopeUserId,
            @Param("includeOtherNonDrafts") boolean includeOtherNonDrafts, @Param("status") ArticleStatus status,
            @Param("categoryIds") Set<Long> categoryIds, @Param("tagId") Long tagId,
            @Param("keyword") String keyword);

    ArticleRow findDetail(@Param("id") Long id, @Param("portal") boolean portal);

    int changeState(@Param("expected") ArticleSnapshot expected, @Param("target") ArticleStatus target);

    int saveDraft(@Param("expected") ArticleSnapshot expected, @Param("target") ArticleStatus target,
            @Param("draft") ArticleDraft draft);

    int markDeleted(@Param("expected") ArticleSnapshot expected);
}
