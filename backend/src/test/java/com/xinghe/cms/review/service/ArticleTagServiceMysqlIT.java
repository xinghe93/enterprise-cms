package com.xinghe.cms.review.service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;

import javax.sql.DataSource;

import com.xinghe.cms.review.mapper.ArticleTagMapper;
import com.xinghe.cms.review.mapper.ArticleTagRow;
import com.xinghe.cms.review.mapper.TagRow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * PR #28 复审要求的两项真实 MySQL 验证（显式通过 -Dtest=ArticleTagServiceMysqlIT 运行）：
 * 1. record 构造器结果映射在真实数据行上可用（status 为基本类型 int，需 javaType="_int"）；
 * 2. 并发删除标签关联后替换标签时，selectBoundTagIds 以加锁当前读看到最新数据，不会漏插。
 * 类名以 IT 结尾，默认 surefire 不包含，避免每次 mvn test 都依赖真实库。
 */
@SpringBootTest
class ArticleTagServiceMysqlIT {

    private static final long USER_ID = 991201L;
    private static final long CATEGORY_ID = 991101L;
    private static final long ARTICLE_ID = 990201L;
    private static final long TAG_A = 991301L;
    private static final long TAG_B = 991302L;
    private static final long TAG_C = 991303L;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private ArticleTagMapper articleTagMapper;

    @Autowired
    private ArticleTagService articleTagService;

    @BeforeEach
    void seed() {
        cleanup();
        jdbc.update("INSERT INTO sys_user (id, username, password_hash, real_name, department_id, status) "
                + "VALUES (?, ?, ?, ?, NULL, 1)", USER_ID, "it_reviewer", "not-a-real-hash", "IT用户");
        jdbc.update("INSERT INTO cms_category (id, parent_id, category_name, sort_order, status) "
                + "VALUES (?, NULL, ?, 0, 1)", CATEGORY_ID, "IT分类");
        jdbc.update("INSERT INTO cms_tag (id, tag_name, status) VALUES (?, ?, 1)", TAG_A, "IT标签A");
        jdbc.update("INSERT INTO cms_tag (id, tag_name, status) VALUES (?, ?, 1)", TAG_B, "IT标签B");
        jdbc.update("INSERT INTO cms_tag (id, tag_name, status) VALUES (?, ?, 1)", TAG_C, "IT标签C");
        jdbc.update("INSERT INTO cms_article (id, title, summary, content, category_id, author_id, status, visibility, version) "
                + "VALUES (?, ?, NULL, ?, ?, ?, 'DRAFT', 'ALL', 0)", ARTICLE_ID, "IT文章", "正文内容",
                CATEGORY_ID, USER_ID);
        jdbc.update("INSERT INTO cms_article_tag (article_id, tag_id) VALUES (?, ?)", ARTICLE_ID, TAG_B);
        jdbc.update("INSERT INTO cms_article_tag (article_id, tag_id) VALUES (?, ?)", ARTICLE_ID, TAG_C);
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM cms_article_tag WHERE article_id = ?", ARTICLE_ID);
        jdbc.update("DELETE FROM cms_article WHERE id = ?", ARTICLE_ID);
        jdbc.update("DELETE FROM cms_tag WHERE id IN (?, ?, ?)", TAG_A, TAG_B, TAG_C);
        jdbc.update("DELETE FROM cms_category WHERE id = ?", CATEGORY_ID);
        jdbc.update("DELETE FROM sys_user WHERE id = ?", USER_ID);
    }

    @Test
    void mapsRealMysqlRowsIntoRecordsWithPrimitiveStatus() {
        List<TagRow> locked = articleTagMapper.lockTagsByIds(List.of(TAG_A, TAG_B));

        assertThat(locked)
                .extracting(TagRow::id, TagRow::tagName, TagRow::status)
                .containsExactly(tuple(TAG_A, "IT标签A", 1), tuple(TAG_B, "IT标签B", 1));

        List<ArticleTagRow> rows = articleTagMapper.selectTagRowsByArticleIds(List.of(ARTICLE_ID));

        assertThat(rows)
                .extracting(ArticleTagRow::articleId, ArticleTagRow::tagId, ArticleTagRow::tagName, ArticleTagRow::status)
                .containsExactly(tuple(ARTICLE_ID, TAG_B, "IT标签B", 1), tuple(ARTICLE_ID, TAG_C, "IT标签C", 1));
    }

    @Test
    void seesConcurrentlyDeletedBindingBeforeReplacing() throws Exception {
        transactionTemplate.executeWithoutResult(status -> {
            // 先普通 SELECT 建立 REPEATABLE READ 快照（模拟外层在锁文章之前已经查询过数据）。
            jdbc.queryForList("SELECT tag_id FROM cms_article_tag WHERE article_id = ?", Long.class, ARTICLE_ID);
            // 外层按契约持文章行锁。
            jdbc.queryForObject("SELECT id FROM cms_article WHERE id = ? FOR UPDATE", Long.class, ARTICLE_ID);
            // 另一条连接删除 tagC 的绑定并提交。
            deleteBindingInAnotherTransaction(ARTICLE_ID, TAG_C);
            // 请求 {tagB, tagC}：tagC 已被并发删除，必须重新插入。
            articleTagService.replaceBindings(ARTICLE_ID, List.of(TAG_B, TAG_C));
        });

        List<Long> boundTagIds = jdbc.queryForList(
                "SELECT tag_id FROM cms_article_tag WHERE article_id = ? ORDER BY tag_id ASC",
                Long.class, ARTICLE_ID);
        assertThat(boundTagIds).containsExactly(TAG_B, TAG_C);
    }

    private void deleteBindingInAnotherTransaction(long articleId, long tagId) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM cms_article_tag WHERE article_id = ? AND tag_id = ?")) {
                statement.setLong(1, articleId);
                statement.setLong(2, tagId);
                statement.executeUpdate();
            }
            connection.commit();
        } catch (Exception exception) {
            throw new IllegalStateException("并发删除标签关联失败", exception);
        }
    }
}
