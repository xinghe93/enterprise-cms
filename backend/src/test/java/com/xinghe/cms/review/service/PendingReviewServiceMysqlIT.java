package com.xinghe.cms.review.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.xinghe.cms.common.contract.CurrentUser;
import com.xinghe.cms.common.enums.ArticleStatus;
import com.xinghe.cms.common.response.PageResult;
import com.xinghe.cms.review.dto.PendingReviewQuery;
import com.xinghe.cms.review.vo.ArticleSummaryVO;
import com.xinghe.cms.security.CurrentUserProvider;
import com.xinghe.cms.support.GuardedDataSource;
import com.xinghe.cms.support.TestDatabaseGuard;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 待审分页的真实 MySQL 验证：只返回待审且未删除、排除审核人自己的文章、按 updated_at 升序；
 * 关键词按字面包含（{@code %}、{@code _} 不具通配语义）、分类筛选含全部后代、
 * 标签按 ID 升序且包含已绑定停用标签、分页元数据正确。
 *
 * <p>测试库隔离沿用 PR #28 方案：每次运行创建专属可丢弃库 {@code cms_it_<runId>_pending}，
 * 建库/建表/库名校验在同一连接完成；数据源被 {@link GuardedDataSource} 包装，
 * 任何一次取连接都会先精确校验库名，配置指错库时立即失败且不执行任何 DDL/DML。
 * 实例不可用时整类跳过，不影响常规 {@code mvn verify}。</p>
 */
@SpringBootTest
class PendingReviewServiceMysqlIT {

    private static final String IT_DATABASE = "cms_it_" + Long.toHexString(System.nanoTime()) + "_pending";
    private static final String DEFAULT_URL =
            "jdbc:mysql://127.0.0.1:3306/enterprise_cms?useSSL=false&allowPublicKeyRetrieval=true"
                    + "&serverTimezone=Asia/Shanghai&characterEncoding=utf8";

    private static final long AUTHOR_ID = 991201L;
    private static final long REVIEWER_ID = 991202L;
    private static final long ROOT_CATEGORY_ID = 991101L;
    private static final long CHILD_CATEGORY_ID = 991102L;
    private static final long OTHER_CATEGORY_ID = 991103L;
    private static final long TAG_ENABLED_ID = 991301L;
    private static final long TAG_DISABLED_ID = 991302L;

    private static final long ARTICLE_OLDEST = 990201L;
    private static final long ARTICLE_SECOND = 990202L;
    private static final long ARTICLE_CHILD = 990203L;
    private static final long ARTICLE_DRAFT = 990204L;
    private static final long ARTICLE_APPROVED = 990205L;
    private static final long ARTICLE_SELF = 990206L;
    private static final long ARTICLE_PERCENT = 990207L;
    private static final long ARTICLE_PLAIN = 990208L;

    private static boolean createdDatabase = false;

    @DynamicPropertySource
    static void isolatedDatasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PendingReviewServiceMysqlIT::itUrl);
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PendingReviewService pendingReviewService;

    @BeforeEach
    void seed() {
        TestDatabaseGuard.requireDatabase(jdbc, IT_DATABASE);
        cleanupIfIsolated();
        jdbc.update("INSERT INTO sys_user (id, username, password_hash, real_name, department_id, status) "
                + "VALUES (?, ?, ?, ?, NULL, 1)", AUTHOR_ID, "it_author", "not-a-real-hash", "IT作者");
        jdbc.update("INSERT INTO sys_user (id, username, password_hash, real_name, department_id, status) "
                + "VALUES (?, ?, ?, ?, NULL, 1)", REVIEWER_ID, "it_reviewer", "not-a-real-hash", "IT审核人");
        insertCategory(ROOT_CATEGORY_ID, null, "IT根分类");
        insertCategory(CHILD_CATEGORY_ID, ROOT_CATEGORY_ID, "IT子分类");
        insertCategory(OTHER_CATEGORY_ID, null, "IT其他分类");
        jdbc.update("INSERT INTO cms_tag (id, tag_name, status) VALUES (?, ?, 1)", TAG_ENABLED_ID, "IT启用标签");
        jdbc.update("INSERT INTO cms_tag (id, tag_name, status) VALUES (?, ?, 0)", TAG_DISABLED_ID, "IT停用标签");

        insertArticle(ARTICLE_OLDEST, "待审文章A", null, ROOT_CATEGORY_ID, AUTHOR_ID,
                ArticleStatus.PENDING_REVIEW, localTime(10, 0));
        insertArticle(ARTICLE_SECOND, "待审文章B", null, ROOT_CATEGORY_ID, AUTHOR_ID,
                ArticleStatus.PENDING_REVIEW, localTime(10, 5));
        insertArticle(ARTICLE_CHILD, "子分类待审文章", null, CHILD_CATEGORY_ID, AUTHOR_ID,
                ArticleStatus.PENDING_REVIEW, localTime(10, 10));
        insertArticle(ARTICLE_DRAFT, "草稿文章", null, ROOT_CATEGORY_ID, AUTHOR_ID,
                ArticleStatus.DRAFT, localTime(10, 12));
        insertArticle(ARTICLE_APPROVED, "已通过文章", null, ROOT_CATEGORY_ID, AUTHOR_ID,
                ArticleStatus.APPROVED, localTime(10, 13));
        insertArticle(ARTICLE_SELF, "审核人自己的待审文章", null, ROOT_CATEGORY_ID, REVIEWER_ID,
                ArticleStatus.PENDING_REVIEW, localTime(10, 14));
        insertArticle(ARTICLE_PERCENT, "100% 完成", null, ROOT_CATEGORY_ID, AUTHOR_ID,
                ArticleStatus.PENDING_REVIEW, localTime(10, 15));
        insertArticle(ARTICLE_PLAIN, "100X 完成", null, ROOT_CATEGORY_ID, AUTHOR_ID,
                ArticleStatus.PENDING_REVIEW, localTime(10, 20));
        jdbc.update("INSERT INTO cms_article_tag (article_id, tag_id) VALUES (?, ?)",
                ARTICLE_SECOND, TAG_DISABLED_ID);
        jdbc.update("INSERT INTO cms_article_tag (article_id, tag_id) VALUES (?, ?)",
                ARTICLE_SECOND, TAG_ENABLED_ID);
        bindCurrentUser(REVIEWER_ID);
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
        cleanupIfIsolated();
    }

    @Test
    void returnsOnlyPendingArticlesExcludingOwnAndSortsByUpdatedAtAscending() {
        PageResult<ArticleSummaryVO> result = pendingReviewService.pending(query());

        assertThat(result.total()).isEqualTo(5);
        assertThat(result.records())
                .extracting(ArticleSummaryVO::getId)
                .containsExactly(ARTICLE_OLDEST, ARTICLE_SECOND, ARTICLE_CHILD, ARTICLE_PERCENT, ARTICLE_PLAIN);
        assertThat(result.records())
                .extracting(ArticleSummaryVO::getStatus)
                .containsOnly(ArticleStatus.PENDING_REVIEW);
        assertThat(result.records())
                .extracting(ArticleSummaryVO::getAuthorId)
                .doesNotContain(REVIEWER_ID);
    }

    @Test
    void paginatesByRequestedPage() {
        PendingReviewQuery query = query();
        query.setCurrent(2);
        query.setSize(2);

        PageResult<ArticleSummaryVO> result = pendingReviewService.pending(query);

        assertThat(result.total()).isEqualTo(5);
        assertThat(result.current()).isEqualTo(2);
        assertThat(result.size()).isEqualTo(2);
        assertThat(result.records())
                .extracting(ArticleSummaryVO::getId)
                .containsExactly(ARTICLE_CHILD, ARTICLE_PERCENT);
    }

    @Test
    void matchesKeywordLiterallySoPercentIsNotWildcard() {
        PendingReviewQuery query = query();
        query.setKeyword("100%");

        PageResult<ArticleSummaryVO> result = pendingReviewService.pending(query);

        assertThat(result.total()).isEqualTo(1);
        assertThat(result.records()).extracting(ArticleSummaryVO::getId).containsExactly(ARTICLE_PERCENT);
    }

    @Test
    void filtersByCategoryAndDescendants() {
        PendingReviewQuery query = query();
        query.setCategoryId(String.valueOf(ROOT_CATEGORY_ID));

        PageResult<ArticleSummaryVO> result = pendingReviewService.pending(query);

        assertThat(result.total()).isEqualTo(5);
        assertThat(result.records()).extracting(ArticleSummaryVO::getId).contains(ARTICLE_CHILD);

        PendingReviewQuery childOnly = query();
        childOnly.setCategoryId(String.valueOf(CHILD_CATEGORY_ID));
        PageResult<ArticleSummaryVO> childResult = pendingReviewService.pending(childOnly);
        assertThat(childResult.total()).isEqualTo(1);
        assertThat(childResult.records()).extracting(ArticleSummaryVO::getId).containsExactly(ARTICLE_CHILD);
    }

    @Test
    void assemblesTagsInAscendingOrderAndKeepsDisabledBinding() {
        PendingReviewQuery query = query();
        query.setKeyword("待审文章B");

        PageResult<ArticleSummaryVO> result = pendingReviewService.pending(query);

        assertThat(result.records()).hasSize(1);
        assertThat(result.records().getFirst().getTags())
                .extracting(tag -> tag.getId(), tag -> tag.getStatus())
                .containsExactly(tuple(TAG_ENABLED_ID, 1), tuple(TAG_DISABLED_ID, 0));
    }

    @Test
    void returnsEmptyTagsForArticleWithoutBinding() {
        PendingReviewQuery query = query();
        query.setKeyword("待审文章A");

        PageResult<ArticleSummaryVO> result = pendingReviewService.pending(query);

        assertThat(result.records()).hasSize(1);
        assertThat(result.records().getFirst().getTags()).isEmpty();
    }

    // ---------------------------------------------------------------- 造数与清理

    private static PendingReviewQuery query() {
        PendingReviewQuery query = new PendingReviewQuery();
        query.setCurrent(1);
        query.setSize(10);
        return query;
    }

    private static LocalDateTime localTime(int hour, int minute) {
        return LocalDateTime.of(2026, 11, 15, hour, minute);
    }

    private void insertCategory(long id, Long parentId, String name) {
        jdbc.update("INSERT INTO cms_category (id, parent_id, category_name, sort_order, status) "
                + "VALUES (?, ?, ?, 0, 1)", id, parentId, name);
    }

    private void insertArticle(long id, String title, String summary, long categoryId, long authorId,
            ArticleStatus status, LocalDateTime updatedAt) {
        Timestamp timestamp = Timestamp.valueOf(updatedAt);
        jdbc.update("INSERT INTO cms_article (id, title, summary, content, category_id, author_id, "
                        + "status, visibility, version, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, 'ALL', 0, ?, ?)",
                id, title, summary, "正文内容", categoryId, authorId, status.name(), timestamp, timestamp);
    }

    private static void bindCurrentUser(long userId) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(CurrentUserProvider.REQUEST_ATTRIBUTE, new CurrentUser(userId, "it_reviewer", null));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    private void cleanupIfIsolated() {
        try {
            TestDatabaseGuard.requireDatabase(jdbc, IT_DATABASE);
        } catch (IllegalStateException skipped) {
            return;
        }
        jdbc.update("DELETE FROM cms_article_tag WHERE article_id IN (?, ?, ?, ?, ?, ?, ?, ?)",
                ARTICLE_OLDEST, ARTICLE_SECOND, ARTICLE_CHILD, ARTICLE_DRAFT,
                ARTICLE_APPROVED, ARTICLE_SELF, ARTICLE_PERCENT, ARTICLE_PLAIN);
        jdbc.update("DELETE FROM cms_article WHERE id IN (?, ?, ?, ?, ?, ?, ?, ?)",
                ARTICLE_OLDEST, ARTICLE_SECOND, ARTICLE_CHILD, ARTICLE_DRAFT,
                ARTICLE_APPROVED, ARTICLE_SELF, ARTICLE_PERCENT, ARTICLE_PLAIN);
        jdbc.update("DELETE FROM cms_tag WHERE id IN (?, ?)", TAG_ENABLED_ID, TAG_DISABLED_ID);
        // 分类自引用外键：先删子分类，再删父分类。
        jdbc.update("DELETE FROM cms_category WHERE id = ?", CHILD_CATEGORY_ID);
        jdbc.update("DELETE FROM cms_category WHERE id IN (?, ?)", ROOT_CATEGORY_ID, OTHER_CATEGORY_ID);
        jdbc.update("DELETE FROM sys_user WHERE id IN (?, ?)", AUTHOR_ID, REVIEWER_ID);
    }

    // ---------------------------------------------------------------- 独立测试库准备

    @BeforeAll
    static void provisionItDatabase() {
        String username = envOr("SPRING_DATASOURCE_USERNAME", "root");
        String password = envOr("SPRING_DATASOURCE_PASSWORD", "");
        try (Connection connection = DriverManager.getConnection(serverUrl(), username, password)) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE DATABASE `" + IT_DATABASE
                        + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
                statement.execute("USE `" + IT_DATABASE + "`");
                createdDatabase = true;
            }
            requireConnectedDatabase(connection);
            Path script = locateInitScript();
            String rawScript = Files.readString(script, StandardCharsets.UTF_8);
            int useIndex = rawScript.indexOf("USE enterprise_cms;");
            if (useIndex < 0) {
                throw new IllegalStateException("初始化脚本缺少 USE enterprise_cms; 声明，无法隔离建表目标库");
            }
            String sql = rawScript.substring(useIndex + "USE enterprise_cms;".length());
            ScriptUtils.executeSqlScript(connection,
                    new EncodedResource(new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8)),
                            StandardCharsets.UTF_8));
            requireConnectedDatabase(connection);
        } catch (SQLException | IOException exception) {
            Assumptions.assumeTrue(false, "跳过待审分页 MySQL 集成测试：无法准备本轮专有测试库（"
                    + exception.getMessage() + "）");
        }
    }

    @AfterAll
    static void dropItDatabase() {
        if (!createdDatabase || !IT_DATABASE.startsWith("cms_it_")) {
            return;
        }
        try (Connection connection = DriverManager.getConnection(serverUrl(),
                envOr("SPRING_DATASOURCE_USERNAME", "root"), envOr("SPRING_DATASOURCE_PASSWORD", ""));
             Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS `" + IT_DATABASE + "`");
        } catch (SQLException ignored) {
            // 清理失败不覆盖测试结论，库本身是本次运行创建的一次性库。
        }
    }

    @TestConfiguration
    static class GuardedDataSourceConfiguration {

        @Bean
        static BeanPostProcessor guardedDataSourcePostProcessor() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if (bean instanceof DataSource dataSource && "dataSource".equals(beanName)) {
                        return new GuardedDataSource(dataSource, IT_DATABASE);
                    }
                    return bean;
                }
            };
        }
    }

    private static void requireConnectedDatabase(Connection connection) {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT DATABASE()")) {
            String actualDatabase = resultSet.next() ? resultSet.getString(1) : null;
            if (!IT_DATABASE.equals(actualDatabase)) {
                throw new IllegalStateException("初始化脚本未切换到独立测试库 " + IT_DATABASE
                        + "，实际连接 " + actualDatabase);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("无法校验独立测试库 " + IT_DATABASE + " 的连接目标", exception);
        }
    }

    private static Path locateInitScript() {
        Path fromBackend = Path.of("..", "database", "001_init.sql");
        if (Files.exists(fromBackend)) {
            return fromBackend;
        }
        Path fromRoot = Path.of("database", "001_init.sql");
        if (Files.exists(fromRoot)) {
            return fromRoot;
        }
        throw new IllegalStateException("找不到 database/001_init.sql，无法初始化独立测试库");
    }

    private static String itUrl() {
        return baseUrl() + IT_DATABASE + queryPart();
    }

    private static String serverUrl() {
        // 建库/删库连接与业务测试连接必须使用同一套参数（认证、TLS、serverTimezone 等），
        // 否则需要显式认证参数的 MySQL 实例会在建库阶段失败，导致整个 IT 类被跳过。
        return baseUrl() + queryPart();
    }

    private static String baseUrl() {
        String url = envOr("SPRING_DATASOURCE_URL", DEFAULT_URL);
        String withoutQuery = url;
        int query = url.indexOf('?');
        if (query >= 0) {
            withoutQuery = url.substring(0, query);
        }
        return withoutQuery.substring(0, withoutQuery.lastIndexOf('/') + 1);
    }

    private static String queryPart() {
        String url = envOr("SPRING_DATASOURCE_URL", DEFAULT_URL);
        int query = url.indexOf('?');
        return query >= 0 ? url.substring(query) : "";
    }

    private static String envOr(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
