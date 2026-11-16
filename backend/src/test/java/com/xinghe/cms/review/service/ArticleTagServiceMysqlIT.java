package com.xinghe.cms.review.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import com.xinghe.cms.common.exception.BusinessException;
import com.xinghe.cms.review.mapper.ArticleTagMapper;
import com.xinghe.cms.review.mapper.ArticleTagRow;
import com.xinghe.cms.review.mapper.TagRow;
import com.xinghe.cms.support.GuardedDataSource;
import com.xinghe.cms.support.TestDatabaseGuard;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
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
 * <p>测试库隔离：本类使用<b>每次运行专属</b>的可丢弃库（库名 {@code cms_it_<runId>_tag}），
 * 启动时用不带 IF NOT EXISTS 的 CREATE DATABASE 创建并按 {@code database/001_init.sql} 建表，
 * 库名校验与建库/建表在同一个连接上完成；数据源被包装成 {@link GuardedDataSource}，
 * 测试写入、Service 事务与收尾清理的每个连接都会先校验实际库名，不匹配立即终止，
 * 不会触碰业务库中的任何数据。运行结束后只删除本轮实际创建的库。</p>
 *
 * <p>类名以 IT 结尾，默认 surefire 不包含，避免每次 mvn test 都依赖真实库。</p>
 */
@SpringBootTest
class ArticleTagServiceMysqlIT {

    /**
     * 本次运行专属的测试库：带时间戳后缀，两轮测试并行时各自拿到不同库，
     * 不会复用上一次运行残留的库。
     */
    private static final String IT_DATABASE = "cms_it_" + Long.toHexString(System.nanoTime()) + "_tag";
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

    private static boolean createdDatabase = false;

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
        TestDatabaseGuard.requireDatabase(jdbc, IT_DATABASE);
        cleanupIfIsolated();
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
        cleanupIfIsolated();
    }

    /**
     * 收尾清理：DELETE 之前必须校验实际连接的库名；Hikari 配置可能覆盖测试数据源，
     * 即使 {@code @BeforeEach} 断言失败，{@code @AfterEach} 仍会执行，因此这里自带保护。
     * 库名不匹配时跳过全部删除，绝不触碰业务库数据。
     */
    private void cleanupIfIsolated() {
        try {
            TestDatabaseGuard.requireDatabase(jdbc, IT_DATABASE);
        } catch (IllegalStateException skipped) {
            return;
        }
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

    /**
     * 每次运行创建一个专属、可丢弃的测试库（库名带时间戳，不使用 IF NOT EXISTS），
     * 并在<b>同一个连接</b>上完成建库、切换库、执行建表脚本与两次库名校验；
     * 测试写入与清理则通过 {@link GuardedDataSource} 在每次借用连接时校验库名，
     * 保证校验与 DDL/DML 使用同一个物理连接。
     */
    @BeforeAll
    static void provisionItDatabase() {
        String username = envOr("SPRING_DATASOURCE_USERNAME", "root");
        String password = envOr("SPRING_DATASOURCE_PASSWORD", "");
        try (Connection connection = DriverManager.getConnection(serverUrl(), username, password)) {
            try (Statement statement = connection.createStatement()) {
                // 不带 IF NOT EXISTS：库名带本次运行标识，创建失败说明冲突或环境异常，直接暴露。
                statement.execute("CREATE DATABASE `" + IT_DATABASE
                        + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
                statement.execute("USE `" + IT_DATABASE + "`");
                // 库一创建成功就记为"本轮创建"，即使后续建表失败也能在 @AfterAll 中清理自己的库。
                createdDatabase = true;
            }
            requireConnectedDatabase(connection);
            Path script = locateInitScript();
            // 建库与切换库已由测试在同一连接上完成：截去脚本头部的 CREATE DATABASE / USE 声明，
            // 只执行建表语句，保证所有 DDL 落在本轮专属库、且不会重跑全局建库语句。
            String rawScript = Files.readString(script, StandardCharsets.UTF_8);
            int useIndex = rawScript.indexOf("USE enterprise_cms;");
            if (useIndex < 0) {
                throw new IllegalStateException("初始化脚本缺少 USE enterprise_cms; 声明，无法隔离建表目标库");
            }
            String sql = rawScript.substring(useIndex + "USE enterprise_cms;".length());
            ScriptUtils.executeSqlScript(connection,
                    new EncodedResource(new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8));
            requireConnectedDatabase(connection);
        } catch (SQLException | IOException exception) {
            Assumptions.assumeTrue(false, "跳过标签关联 MySQL 集成测试：无法准备本轮专有测试库（"
                    + exception.getMessage() + "）");
        }
    }

    @AfterAll
    static void dropItDatabase() {
        // 只清理本轮实际创建的库：库名必须以本次运行标识开头。
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

    /**
     * 把自动配置的数据源包装成 {@link GuardedDataSource}：任何一次连接池取连接（测试准备、
     * Service 写入、事务、收尾清理）都会先在实际连接上校验库名，配置指错库时立刻失败，
     * 不会执行任何 DDL/DML。
     */
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

    /** 建表脚本执行后校验连接实际落在本次运行分配的独立测试库上。 */
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
