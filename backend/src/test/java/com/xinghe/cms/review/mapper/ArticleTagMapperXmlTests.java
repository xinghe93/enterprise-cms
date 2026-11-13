package com.xinghe.cms.review.mapper;

import java.util.List;
import java.util.Map;

import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 校验文章标签 mapper XML 已加载并生成预期的动态 SQL；不连接 MySQL。
 */
@SpringBootTest
class ArticleTagMapperXmlTests {

    private static final String NAMESPACE = ArticleTagMapper.class.getName();

    @Autowired
    private SqlSessionFactory sqlSessionFactory;

    @Test
    void loadsAllArticleTagStatements() {
        var configuration = sqlSessionFactory.getConfiguration();

        assertThat(configuration.hasStatement(NAMESPACE + ".lockTagsByIds")).isTrue();
        assertThat(configuration.hasStatement(NAMESPACE + ".insertBindingIfAbsent")).isTrue();
        assertThat(configuration.hasStatement(NAMESPACE + ".insertBindingsIfAbsent")).isTrue();
        assertThat(configuration.hasStatement(NAMESPACE + ".deleteBindingsNotRequested")).isTrue();
        assertThat(configuration.hasStatement(NAMESPACE + ".selectTagRowsByArticleIds")).isTrue();
    }

    @Test
    void lockStatementUsesSharedLockAndAscendingOrder() {
        BoundSql boundSql = boundSql("lockTagsByIds", Map.of("tagIds", List.of(9L, 3L)));

        assertThat(normalize(boundSql.getSql()))
                .contains("FROM cms_tag")
                .contains("ORDER BY id ASC")
                .contains("FOR SHARE");
        assertThat(boundSql.getParameterMappings()).hasSize(2);
    }

    @Test
    void singleInsertStatementIsIdempotent() {
        BoundSql boundSql = boundSql("insertBindingIfAbsent", Map.of("articleId", 1L, "tagId", 3L));

        assertThat(normalize(boundSql.getSql()))
                .startsWith("INSERT IGNORE INTO cms_article_tag (article_id, tag_id) VALUES")
                .contains("(?, ?)");
        assertThat(boundSql.getParameterMappings()).hasSize(2);
    }

    @Test
    void batchInsertStatementIsIdempotentAndBindsOneRowPerTag() {
        BoundSql boundSql = boundSql("insertBindingsIfAbsent", Map.of("articleId", 1L, "tagIds", List.of(3L, 4L)));

        assertThat(normalize(boundSql.getSql()))
                .startsWith("INSERT IGNORE INTO cms_article_tag (article_id, tag_id) VALUES")
                .contains("(?, ?) , (?, ?)");
        assertThat(boundSql.getParameterMappings()).hasSize(4);
    }

    @Test
    void deleteStatementRemovesTagIdsOutsideRequestedSet() {
        BoundSql boundSql = boundSql("deleteBindingsNotRequested", Map.of("articleId", 1L, "tagIds", List.of(3L, 4L)));

        assertThat(normalize(boundSql.getSql()))
                .contains("DELETE FROM cms_article_tag")
                .contains("AND tag_id NOT IN")
                .contains("(? , ?)");
        assertThat(boundSql.getParameterMappings()).hasSize(3);
    }

    @Test
    void deleteStatementClearsAllBindingsWhenRequestedSetIsEmpty() {
        BoundSql boundSql = boundSql("deleteBindingsNotRequested", Map.of("articleId", 1L, "tagIds", List.of()));

        assertThat(normalize(boundSql.getSql()))
                .contains("DELETE FROM cms_article_tag")
                .contains("WHERE article_id = ?")
                .doesNotContain("NOT IN");
        assertThat(boundSql.getParameterMappings()).hasSize(1);
    }

    @Test
    void multiArticleStatementJoinsTagDictionaryAndSorts() {
        BoundSql boundSql = boundSql("selectTagRowsByArticleIds", Map.of("articleIds", List.of(1L, 2L)));

        assertThat(normalize(boundSql.getSql()))
                .contains("INNER JOIN cms_tag tag ON tag.id = link.tag_id")
                .contains("ORDER BY link.article_id ASC, tag.id ASC");
        assertThat(boundSql.getParameterMappings()).hasSize(2);
    }

    private BoundSql boundSql(String statementId, Map<String, Object> parameters) {
        MappedStatement statement = sqlSessionFactory.getConfiguration()
                .getMappedStatement(NAMESPACE + "." + statementId);
        return statement.getBoundSql(parameters);
    }

    private static String normalize(String sql) {
        return sql.replaceAll("\\s+", " ").trim().replace("( ", "(").replace(" )", ")");
    }
}
