package com.xinghe.cms.review.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * cms_article_tag 关联表的持久层；对 cms_tag 只做只读校验（共享锁），不改标签数据。
 */
@Mapper
public interface ArticleTagMapper {

    /**
     * 按 ID 升序对标签加共享锁并读取标签行（校验新绑定标签是否存在、是否停用）。
     */
    List<TagRow> lockTagsByIds(@Param("tagIds") List<Long> tagIds);

    /**
     * 查询一篇文章当前已绑定的标签 ID，按 ID 升序。
     */
    List<Long> selectBoundTagIds(@Param("articleId") Long articleId);

    /**
     * 删除该文章中不在给定集合内的绑定；tagIds 为 null 或空集合时删除该文章全部绑定。
     */
    int deleteBindings(@Param("articleId") Long articleId, @Param("tagIds") List<Long> tagIds);

    /**
     * 批量插入该文章的新绑定；调用方已通过差集保证不会产生重复键。
     */
    int insertBindings(@Param("articleId") Long articleId, @Param("tagIds") List<Long> tagIds);

    /**
     * 一次 JOIN 查询多篇文章的标签行，按文章 ID、标签 ID 升序。
     */
    List<ArticleTagRow> selectTagRowsByArticleIds(@Param("articleIds") List<Long> articleIds);
}
