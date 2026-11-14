package com.xinghe.cms.review.mapper;

import java.util.Map;

import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 校验审核历史 mapper XML 已加载并生成预期的多表查询 SQL；不连接 MySQL。
 */
@SpringBootTest
class ReviewRecordMapperXmlTests {

    private static final String NAMESPACE = ReviewRecordMapper.class.getName();

    @Autowired
    private SqlSessionFactory sqlSessionFactory;

    @Test
    void loadsReviewPageStatement() {
        assertThat(sqlSessionFactory.getConfiguration().hasStatement(NAMESPACE + ".selectReviewPage")).isTrue();
    }

    @Test
    void joinsUserTableAndSortsByReviewedAtThenId() {
        BoundSql boundSql = boundSql("selectReviewPage", Map.of("articleId", 7L));

        assertThat(normalize(boundSql.getSql()))
                .contains("FROM cms_review r")
                .contains("INNER JOIN sys_user u ON u.id = r.reviewer_id")
                .contains("WHERE r.article_id = ?")
                .contains("ORDER BY r.reviewed_at DESC, r.id DESC");
        assertThat(boundSql.getParameterMappings()).hasSize(1);
    }

    private BoundSql boundSql(String statementId, Map<String, Object> parameters) {
        MappedStatement statement = sqlSessionFactory.getConfiguration()
                .getMappedStatement(NAMESPACE + "." + statementId);
        return statement.getBoundSql(parameters);
    }

    private static String normalize(String sql) {
        return sql.replaceAll("\\s+", " ").trim();
    }
}
