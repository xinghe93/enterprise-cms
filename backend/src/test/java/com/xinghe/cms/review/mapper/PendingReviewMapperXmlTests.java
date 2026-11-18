package com.xinghe.cms.review.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 校验待审分页 mapper XML 已加载并生成预期的动态 SQL；不连接 MySQL。
 */
@SpringBootTest
class PendingReviewMapperXmlTests {

    private static final String NAMESPACE = PendingReviewMapper.class.getName();

    @Autowired
    private SqlSessionFactory sqlSessionFactory;

    @Test
    void loadsPendingPageStatement() {
        assertThat(sqlSessionFactory.getConfiguration()
                .hasStatement(NAMESPACE + ".selectPendingPage")).isTrue();
    }

    @Test
    void fixesReviewStateAndExcludesReviewerOwnArticles() {
        BoundSql boundSql = boundSql(Map.of("reviewerId", 7L));

        assertThat(normalize(boundSql.getSql()))
                .contains("FROM cms_article a")
                .contains("INNER JOIN cms_category c ON c.id = a.category_id")
                .contains("INNER JOIN sys_user u ON u.id = a.author_id")
                .contains("a.is_deleted = 0")
                .contains("a.status = 'PENDING_REVIEW'")
                .contains("a.author_id <> ?")
                .contains("ORDER BY a.updated_at ASC, a.id ASC")
                .doesNotContain("a.category_id IN (")
                .doesNotContain("LIKE");
        assertThat(boundSql.getParameterMappings()).hasSize(1);
    }

    @Test
    void bindsOptionalCategoryAuthorAndEscapedKeywordFilters() {
        BoundSql boundSql = boundSql(Map.of(
                "reviewerId", 7L,
                "categoryIds", List.of(11L, 12L),
                "authorId", 21L,
                "keyword", "%100!%%"));

        assertThat(normalize(boundSql.getSql()))
                .contains("a.category_id IN (? , ?)")
                .contains("a.author_id = ?")
                .contains("a.title LIKE ? ESCAPE '!'")
                .contains("a.summary LIKE ? ESCAPE '!'");
        assertThat(boundSql.getParameterMappings()).hasSize(6);
    }

    private BoundSql boundSql(Map<String, Object> parameters) {
        MappedStatement statement = sqlSessionFactory.getConfiguration()
                .getMappedStatement(NAMESPACE + ".selectPendingPage");
        return statement.getBoundSql(parameters);
    }

    private static String normalize(String sql) {
        return sql.replaceAll("\\s+", " ").trim().replace("( ", "(").replace(" )", ")");
    }
}
