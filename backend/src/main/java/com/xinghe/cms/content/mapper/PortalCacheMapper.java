package com.xinghe.cms.content.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface PortalCacheMapper {

    record ArticleStamp(String revision, int version, String authorName) {}

    @Select("""
            SELECT r.article_revision AS revision, a.version, u.real_name AS author_name
            FROM cms_article a
            JOIN sys_user u ON u.id = a.author_id
            LEFT JOIN cms_portal_cache_revision r ON r.id = 1
            WHERE a.id = #{id} AND a.is_deleted = 0
              AND a.status = 'PUBLISHED' AND a.visibility = 'ALL'
            """)
    ArticleStamp articleStamp(@Param("id") long id);

    @Select("SELECT tree_revision FROM cms_portal_cache_revision WHERE id = 1")
    String treeRevision();

    @Update("""
            UPDATE cms_portal_cache_revision
            SET article_revision = COALESCE(#{articleRevision,jdbcType=VARCHAR}, article_revision),
                tree_revision = COALESCE(#{treeRevision,jdbcType=VARCHAR}, tree_revision)
            WHERE id = 1
            """)
    int advanceRevisions(@Param("articleRevision") String articleRevision,
            @Param("treeRevision") String treeRevision);
}
