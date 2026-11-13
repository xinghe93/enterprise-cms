package com.xinghe.cms.review.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.xinghe.cms.common.exception.BusinessException;
import com.xinghe.cms.review.mapper.ArticleTagMapper;
import com.xinghe.cms.review.mapper.ArticleTagRow;
import com.xinghe.cms.review.mapper.TagRow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * PR #28 复审要求的真实 MySQL 验证（显式通过 -Dtest=ArticleTagServiceMysqlIT 运行）：
 * 1. record 构造器结果映射在真实数据行上可用（status 为基本类型 int，需 javaType="_int"）；
 * 2. 替换标签的最终绑定集合严格等于请求集合，停用标签规则正确；
 * 3. 两个事务同时给不同文章首次绑定标签时都成功且关联均写入（不使用关联表加锁读，避免间隙锁死锁）。
 *
 * <p>测试库隔离：本类使用独立、可丢弃的 {@value #IT_DATABASE} 库，启动前自动创建并按
 * {@code database/001_init.sql} 建表；数据源通过 {@code spring.datasource.url} 覆盖，
 * 清理只操作该库，不会触碰业务库中的固定 ID 数据。</p>
 *
 * <p>类名以 IT 结尾，默认 surefire 不包含，避免每次 mvn test 都依赖真实库。</p>
 */
@SpringBootTest
class ArticleTagServiceMysqlIT {

    private static final String IT_DATABASE = "enterprise_cms_it";
    private static final String DEFAULT_URL =
            "jdbc:mysql://127.0.0.1:3306/enterprise_cms?useSSL=false&allowPublicKeyRetrieval=true"
                    + "&serverTimezone=Asia/Shanghai&characterEncoding=utf8";

    private static final long USER_ID = 991201L;
    private static final long CATEGORY_ID = 991101L;
    private static final long ARTICLE_A = 990201L;
    private static final long ARTICLE_B = 990202L;
    private static final long TAG_A = 991301L;
    private static final long TAG_B = 991302L;
    private static final long TAG_C = 991303L;
    private static final long TAG_DISABLED = 991304L;

    static {
        provisionItDatabase();
    }

    @DynamicPropertySource
    static void isolatedDatasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", ArticleTagServiceMysqlIT::itUrl);
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private ArticleTagMapper articleTagMapper;

    @Autowired
    private ArticleTagService articleTagService;

    @BeforeEach
    void seed() {
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class))
                .as("集成测试必须运行在独立测试库")
                .isEqualTo(IT_DATABASE);
        cleanup();
        jdbc.update("INSERT INTO sys_user (id, username, password_hash, real_name, department_id, status) "
                + "VALUES (?, ?, ?, ?, NULL, 1)", USER_ID, "it_reviewer", "not-a-real-hash", "IT用户");
        jdbc.update("INSERT INTO cms_category (id, parent_id, category_name, sort_order, status) "
                + "VALUES (?, NULL, ?, 0, 1)", CATEGORY_ID, "IT分类");
        jdbc.update("INSERT INTO cms_tag (id, tag_name, status) VALUES (?, ?, 1)", TAG_A, "IT标签A");
        jdbc.update("INSERT INTO cms_tag (id, tag_name, status) VALUES (?, ?, 1)", TAG_B, "IT标签B");
        jdbc.update("INSERT INTO cms_tag (id, tag_name, status) VALUES (?, ?, 1)", TAG_C, "IT标签C");
        jdbc.update("INSERT INTO cms_tag (id, tag_name, status) VALUES (?, ?, 0)", TAG_DISABLED, "IT停用标签");
        insertArticle(ARTICLE_A, "IT文章A");
        insertArticle(ARTICLE_B, "IT文章B");
        jdbc.update("INSERT INTO cms_article_tag (article_id, tag_id) VALUES (?, ?)", ARTICLE_A, TAG_B);
        jdbc.update("INSERT INTO cms_article_tag (article_id, tag_id) VALUES (?, ?)", ARTICLE_A, TAG_C);
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM cms_article_tag WHERE article_id IN (?, ?)", ARTICLE_A, ARTICLE_B);
        jdbc.update("DELETE FROM cms_article WHERE id IN (?, ?)", ARTICLE_A, ARTICLE_B);
        jdbc.update("DELETE FROM cms_tag WHERE id IN (?, ?, ?, ?)", TAG_A, TAG_B, TAG_C, TAG_DISABLED);
        jdbc.update("DELETE FROM cms_category WHERE id = ?", CATEGORY_ID);
        jdbc.update("DELETE FROM sys_user WHERE id = ?", USER_ID);
    }

    @Test
    void mapsRealMysqlRowsIntoRecordsWithPrimitiveStatus() {
        List<TagRow> locked = articleTagMapper.lockTagsByIds(List.of(TAG_A, TAG_B, TAG_DISABLED));

        assertThat(locked)
                .extracting(TagRow::id, TagRow::tagName, TagRow::status)
                .containsExactly(
                        tuple(TAG_A, "IT标签A", 1),
                        tuple(TAG_B, "IT标签B", 1),
                        tuple(TAG_DISABLED, "IT停用标签", 0));

        List<ArticleTagRow> rows = articleTagMapper.selectTagRowsByArticleIds(List.of(ARTICLE_A));

        assertThat(rows)
                .extracting(ArticleTagRow::articleId, ArticleTagRow::tagId, ArticleTagRow::tagName, ArticleTagRow::status)
                .containsExactly(tuple(ARTICLE_A, TAG_B, "IT标签B", 1), tuple(ARTICLE_A, TAG_C, "IT标签C", 1));
    }

    @Test
    void replaceWritesExactRequestedSetAndClearsWhenEmpty() {
        inArticleLock(ARTICLE_A, () -> articleTagService.replaceBindings(ARTICLE_A, List.of(TAG_A, TAG_C)));

        assertThat(boundTagIds(ARTICLE_A)).containsExactly(TAG_A, TAG_C);

        inArticleLock(ARTICLE_A, () -> articleTagService.replaceBindings(ARTICLE_A, List.of()));

        assertThat(boundTagIds(ARTICLE_A)).isEmpty();
    }

    @Test
    void keepsAlreadyBoundDisabledTagButRejectsNewOne() {
        jdbc.update("INSERT INTO cms_article_tag (article_id, tag_id) VALUES (?, ?)", ARTICLE_A, TAG_DISABLED);

        inArticleLock(ARTICLE_A, () -> articleTagService.replaceBindings(ARTICLE_A, List.of(TAG_DISABLED)));
        assertThat(boundTagIds(ARTICLE_A)).containsExactly(TAG_DISABLED);

        jdbc.update("DELETE FROM cms_article_tag WHERE article_id = ? AND tag_id = ?", ARTICLE_A, TAG_DISABLED);

        assertThatThrownBy(() -> inArticleLock(ARTICLE_A,
                () -> articleTagService.replaceBindings(ARTICLE_A, List.of(TAG_DISABLED))))
                .isInstanceOf(BusinessException.class)
                .hasMessage("标签已停用");
        assertThat(boundTagIds(ARTICLE_A)).isEmpty();
    }

    @Test
    void concurrentFirstBindOnDifferentArticlesBothSucceed() throws Exception {
        jdbc.update("DELETE FROM cms_article_tag WHERE article_id IN (?, ?)", ARTICLE_A, ARTICLE_B);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = pool.submit(bindAfterArticleLock(ARTICLE_A, TAG_A, ready, start));
            Future<?> second = pool.submit(bindAfterArticleLock(ARTICLE_B, TAG_B, ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).as("两个事务都应拿到文章行锁").isTrue();
            start.countDown();
            first.get(15, TimeUnit.SECONDS);
            second.get(15, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertThat(boundTagIds(ARTICLE_A)).containsExactly(TAG_A);
        assertThat(boundTagIds(ARTICLE_B)).containsExactly(TAG_B);
    }

    private Callable<Void> bindAfterArticleLock(long articleId, long tagId, CountDownLatch ready, CountDownLatch start) {
        return () -> {
            transactionTemplate.executeWithoutResult(status -> {
                jdbc.queryForObject("SELECT id FROM cms_article WHERE id = ? FOR UPDATE", Long.class, articleId);
                ready.countDown();
                try {
                    start.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("等待并发起点被中断", exception);
                }
                articleTagService.replaceBindings(articleId, List.of(tagId));
            });
            return null;
        };
    }

    private void inArticleLock(long articleId, Runnable action) {
        transactionTemplate.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT id FROM cms_article WHERE id = ? FOR UPDATE", Long.class, articleId);
            action.run();
        });
    }

    private List<Long> boundTagIds(long articleId) {
        return jdbc.queryForList(
                "SELECT tag_id FROM cms_article_tag WHERE article_id = ? ORDER BY tag_id ASC",
                Long.class, articleId);
    }

    private void insertArticle(long articleId, String title) {
        jdbc.update("INSERT INTO cms_article (id, title, summary, content, category_id, author_id, status, visibility, version) "
                + "VALUES (?, ?, NULL, ?, ?, ?, 'DRAFT', 'ALL', 0)", articleId, title, "正文内容", CATEGORY_ID, USER_ID);
    }

    // ---------------------------------------------------------------- 独立测试库准备

    private static void provisionItDatabase() {
        String username = envOr("SPRING_DATASOURCE_USERNAME", "root");
        String password = envOr("SPRING_DATASOURCE_PASSWORD", "");
        try (Connection connection = DriverManager.getConnection(serverUrl(), username, password)) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE DATABASE IF NOT EXISTS " + IT_DATABASE
                        + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
            }
            Path script = locateInitScript();
            String sql = Files.readString(script, StandardCharsets.UTF_8).replace("enterprise_cms", IT_DATABASE);
            ScriptUtils.executeSqlScript(connection,
                    new EncodedResource(new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8));
        } catch (SQLException | IOException exception) {
            throw new IllegalStateException("无法准备独立测试库 " + IT_DATABASE + "，请确认 MySQL 可连接", exception);
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
        return baseUrl();
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
