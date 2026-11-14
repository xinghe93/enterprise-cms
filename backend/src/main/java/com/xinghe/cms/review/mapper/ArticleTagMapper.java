package com.xinghe.cms.review.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * cms_article_tag 关联表的持久层；对 cms_tag 只做只读校验（共享锁），不改标签数据。
 *
 * <p>关联表写入采用幂等语句（INSERT IGNORE + 按请求集合删除多余行），不在事务内读取关联表：
 * 既保证最终绑定集合严格等于请求集合，也避免 REPEATABLE READ 下按 (article_id, tag_id)
 * 首列做加锁读时锁住索引间隙、导致不同文章首次绑定标签时互相等待。</p>
 */
@Mapper
public interface ArticleTagMapper {

    /**
     * 按 ID 升序对标签加共享锁并读取标签行（校验标签是否存在、是否停用）。
     */
    List<TagRow> lockTagsByIds(@Param("tagIds") List<Long> tagIds);

    /**
     * 幂等插入单条关联；返回 1 表示本次确实新增了绑定，0 表示该绑定已存在。
     * 用于判断"停用标签是否属于新绑定"。
     */
    int insertBindingIfAbsent(@Param("articleId") Long articleId, @Param("tagId") Long tagId);

    /**
     * 批量幂等插入关联（INSERT IGNORE），已存在的绑定不报错。
     */
    int insertBindingsIfAbsent(@Param("articleId") Long articleId,
                               @Param("tagIds") List<Long> tagIds);

    /**
     * 删除该文章中不在请求集合内的绑定；tagIds 为空时删除该文章的全部绑定。
     */
    int deleteBindingsNotRequested(@Param("articleId") Long articleId,
                                   @Param("tagIds") List<Long> tagIds);

    /**
     * 一次 JOIN 查询多篇文章的标签行，按文章 ID、标签 ID 升序。
     */
    List<ArticleTagRow> selectTagRowsByArticleIds(@Param("articleIds") List<Long> articleIds);
}
