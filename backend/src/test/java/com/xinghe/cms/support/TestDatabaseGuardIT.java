package com.xinghe.cms.support;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * PR #28 复审要求的"数据库隔离生命周期"验证：在独立、可丢弃的 MySQL 实例上，
 * 用<b>每次运行专属</b>的测试库与探针表走完整生命周期（建库、建表、写入、删表），确认：
 *
 * <ol>
 *   <li>连接实际指向另一个测试库时，初始化在<b>任何 DDL/DML 之前</b>失败，
 *       预置的探针表与记录保持原样；失败后的收尾也跳过，不执行任何删除；</li>
 *   <li>连接指向本次运行分配的测试库时，生命周期能完整执行并在收尾时清理干净；</li>
 *   <li>两轮生命周期<b>并行</b>运行时各自使用自己的库，互不影响；</li>
 *   <li>仅大小写不同的库名、空库名都会被拒绝。</li>
 * </ol>
 *
 * <p>库名校验使用 {@link GuardedDataSource}：每次借用连接都会在该连接上执行
 * {@code SELECT DATABASE()}，保证校验与随后的 DDL/DML 使用同一个物理连接。</p>
 *
 * <p>默认连接第二个 MySQL 实例（Docker 容器 {@code mysql8-guard}，端口 {@code 3307}），
 * 可用环境变量覆盖：</p>
 * <ul>
 *   <li>{@code IT_GUARD_DATASOURCE_URL}（默认 {@code jdbc:mysql://127.0.0.1:3307/?...}）</li>
 *   <li>{@code IT_GUARD_DATASOURCE_USERNAME}（默认 {@code root}）</li>
 *   <li>{@code IT_GUARD_DATASOURCE_PASSWORD}（默认空）</li>
 * </ul>
 *
 * <p>实例不可用时整类跳过，不阻塞常规构建；显式运行：
 * {@code mvn -B -o test "-Dtest=TestDatabaseGuardIT"}。</p>
 */
class TestDatabaseGuardIT {

    private static final String DEFAULT_GUARD_URL =
            "jdbc:mysql://127.0.0.1:3307/?useSSL=false&allowPublicKeyRetrieval=true"
                    + "&serverTimezone=Asia/Shanghai&characterEncoding=utf8";

    /** 本次运行专属标识：并发执行的多轮测试各自拿到不同库名。 */
    private static final String RUN_ID = Long.toHexString(System.nanoTime());

    private static final String ASSIGNED_DATABASE = "cms_it_" + RUN_ID + "_a";
    private static final String OTHER_DATABASE = "cms_it_" + RUN_ID + "_b";
    private static final String PARALLEL_DATABASE_ONE = "cms_it_" + RUN_ID + "_p1";
    private static final String PARALLEL_DATABASE_TWO = "cms_it_" + RUN_ID + "_p2";

    /** 一次运行专属的探针表名：避免清理碰到其他运行或示例的同名表。 */
    private static final String PROBE_TABLE = "cleanup_probe_" + RUN_ID;

    private static final List<String> CREATED_DATABASES = new ArrayList<>();

    private JdbcTemplate assignedJdbc;
    private JdbcTemplate otherJdbc;

    @BeforeAll
    static void provisionRunDatabases() {
        try (Connection connection = DriverManager.getConnection(serverUrl(), username(), password());
             Statement statement = connection.createStatement()) {
            for (String database : List.of(ASSIGNED_DATABASE, OTHER_DATABASE,
                    PARALLEL_DATABASE_ONE, PARALLEL_DATABASE_TWO)) {
                // 不带 IF NOT EXISTS：库名带本次运行标识，创建失败说明命名冲突或环境异常，直接暴露。
                statement.execute("CREATE DATABASE `" + database
                        + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
                CREATED_DATABASES.add(database);
            }
        } catch (SQLException exception) {
            CREATED_DATABASES.clear();
            Assumptions.assumeTrue(false, "跳过数据库隔离验证：无法在独立 MySQL 实例上创建本轮专有测试库（"
                    + exception.getMessage() + "）；请启动 Docker 容器 mysql8-guard，或用 "
                    + "IT_GUARD_DATASOURCE_URL 指定独立实例");
        }
    }

    @AfterAll
    static void dropCreatedDatabases() {
        for (String database : CREATED_DATABASES) {
            // 只清理本轮实际创建的库：库名必须以本轮运行标识开头。
            if (!database.startsWith("cms_it_" + RUN_ID)) {
                continue;
            }
            try (Connection connection = DriverManager.getConnection(serverUrl(), username(), password());
                 Statement statement = connection.createStatement()) {
                statement.execute("DROP DATABASE IF EXISTS `" + database + "`");
            } catch (SQLException ignored) {
                // 清理失败不覆盖测试结论，库本身是本次运行创建的一次性库。
            }
        }
        CREATED_DATABASES.clear();
    }

    @BeforeEach
    void setUp() {
        assignedJdbc = guardedJdbc(ASSIGNED_DATABASE, ASSIGNED_DATABASE);
        otherJdbc = guardedJdbc(OTHER_DATABASE, OTHER_DATABASE);

        // 预置"另一个测试库"中的探针表与记录：若任何一轮越过库名校验，
        // 这张表与这行记录就会被误删，测试即可发现。校验与 DDL 在同一连接上。
        inSingleConnection(otherJdbc, connection -> {
            TestDatabaseGuard.requireDatabase(connection, OTHER_DATABASE);
            executeSql(connection, "CREATE TABLE `" + PROBE_TABLE + "` (id BIGINT PRIMARY KEY)");
            executeSql(connection, "INSERT INTO `" + PROBE_TABLE + "` (id) VALUES (1)");
        });
    }

    @AfterEach
    void tearDown() {
        cleanupQuietly(OTHER_DATABASE, otherJdbc);
        cleanupQuietly(ASSIGNED_DATABASE, assignedJdbc);
    }

    @Test
    void failsBeforeAnyDdlWhenConnectedToAnotherTestDatabaseAndKeepsProbeDataOnTeardown() {
        // 该数据源实际在 OTHER_DATABASE，但传入的期望库是 ASSIGNED_DATABASE。
        JdbcTemplate mismatched = guardedJdbc(OTHER_DATABASE, ASSIGNED_DATABASE);

        assertThatThrownBy(() -> mismatched.execute("DROP TABLE IF EXISTS `" + PROBE_TABLE + "`"))
                .isInstanceOfAny(IllegalStateException.class, org.springframework.dao.DataAccessException.class)
                // 连接池在获取连接阶段就会失败，顶层消息由 Spring 包装，校验完整异常链中的拒绝信息。
                .hasStackTraceContaining("拒绝在非隔离测试库上执行数据库操作")
                .hasStackTraceContaining(ASSIGNED_DATABASE)
                .hasStackTraceContaining(OTHER_DATABASE);

        // 初始化失败 → 预置的探针表与记录仍在，且没有被本测试重建。
        assertThat(probeTableExists(otherJdbc)).isTrue();
        assertThat(probeRowCount(otherJdbc)).isEqualTo(1);

        // 失败后的收尾也必须跳过：再走一次收尾逻辑，仍然不执行任何删除。
        cleanupQuietly(OTHER_DATABASE, mismatched);
        assertThat(probeTableExists(otherJdbc)).as("失败后收尾不得删除任何表").isTrue();
        assertThat(probeRowCount(otherJdbc)).as("失败后收尾不得删除任何记录").isEqualTo(1);
    }

    @Test
    void runsLifecycleAndCleansUpWhenConnectedToAssignedTestDatabase() {
        runGuardedLifecycle(assignedJdbc, ASSIGNED_DATABASE);

        // 生命周期正常走完，收尾已删除本次运行的探针表。
        assertThat(probeTableExists(assignedJdbc)).isFalse();

        // 另一个测试库中的预置数据完全未被触碰。
        assertThat(probeTableExists(otherJdbc)).isTrue();
        assertThat(probeRowCount(otherJdbc)).isEqualTo(1);
    }

    @Test
    void parallelRoundsUseTheirOwnDatabasesWithoutInterference() throws Exception {
        JdbcTemplate first = guardedJdbc(PARALLEL_DATABASE_ONE, PARALLEL_DATABASE_ONE);
        JdbcTemplate second = guardedJdbc(PARALLEL_DATABASE_TWO, PARALLEL_DATABASE_TWO);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> firstRound = pool.submit(() -> runRound(first, PARALLEL_DATABASE_ONE, ready, start));
            Future<?> secondRound = pool.submit(() -> runRound(second, PARALLEL_DATABASE_TWO, ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).as("两轮测试都应准备就绪").isTrue();
            start.countDown();
            firstRound.get(15, TimeUnit.SECONDS);
            secondRound.get(15, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertThat(probeTableExists(first)).as("第一轮清理后不应残留探针表").isFalse();
        assertThat(probeTableExists(second)).as("第二轮清理后不应残留探针表").isFalse();
        // 第三个库（预置数据）完全不受两轮测试影响。
        assertThat(probeRowCount(otherJdbc)).isEqualTo(1);
    }

    @Test
    void rejectsDatabaseNameThatDiffersOnlyByCase() {
        assertThatThrownBy(() -> TestDatabaseGuard.requireDatabase(
                assignedJdbc, ASSIGNED_DATABASE.toUpperCase()))
                .isInstanceOfAny(IllegalStateException.class, org.springframework.dao.DataAccessException.class)
                .hasMessageContaining("拒绝在非隔离测试库上执行数据库操作")
                .hasMessageContaining(ASSIGNED_DATABASE);
    }

    @Test
    void rejectsBlankExpectedDatabase() {
        assertThatThrownBy(() -> TestDatabaseGuard.requireDatabase(assignedJdbc, " "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GuardedDataSource(new DriverManagerDataSource(
                dbUrl(ASSIGNED_DATABASE), username(), password()), " "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---------------------------------------------------------------- 被验证的生命周期

    private static void runRound(JdbcTemplate jdbc, String database,
                                 CountDownLatch ready, CountDownLatch start) {
        ready.countDown();
        try {
            start.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待并发起点被中断", exception);
        }
        runGuardedLifecycle(jdbc, database);
    }

    /**
     * 模拟一次测试的完整生命周期：建表、数据准备、删除都先在同一连接上校验实际库名。
     */
    private static void runGuardedLifecycle(JdbcTemplate jdbc, String assignedDatabase) {
        inSingleConnection(jdbc, connection -> {
            TestDatabaseGuard.requireDatabase(connection, assignedDatabase);
            executeSql(connection, "CREATE TABLE `" + PROBE_TABLE + "` (id BIGINT PRIMARY KEY)");
            executeSql(connection, "INSERT INTO `" + PROBE_TABLE + "` (id) VALUES (7)");
        });
        assertThat(probeRowCount(jdbc)).isEqualTo(1);
        cleanupQuietly(assignedDatabase, jdbc);
    }

    /** 收尾：库名校验通过才删表；不匹配时静默跳过，绝不执行删除。 */
    private static void cleanupQuietly(String assignedDatabase, JdbcTemplate jdbc) {
        if (jdbc == null) {
            return;
        }
        try {
            inSingleConnection(jdbc, connection -> {
                TestDatabaseGuard.requireDatabase(connection, assignedDatabase);
                executeSql(connection, "DROP TABLE IF EXISTS `" + PROBE_TABLE + "`");
            });
        } catch (RuntimeException skipped) {
            // 库名不匹配或连接不可用：收尾跳过，保留数据，交由人工排查配置。
        }
    }

    // ---------------------------------------------------------------- 连接与断言辅助

    private static JdbcTemplate guardedJdbc(String database, String expectedDatabase) {
        return new JdbcTemplate(new GuardedDataSource(
                new DriverManagerDataSource(dbUrl(database), username(), password()),
                expectedDatabase));
    }

    private static void inSingleConnection(JdbcTemplate jdbc, SqlWork work) {
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            work.run(connection);
            return null;
        });
    }

    private static void executeSql(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static boolean probeTableExists(JdbcTemplate jdbc) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables"
                        + " WHERE table_schema = DATABASE() AND table_name = ?",
                Integer.class, PROBE_TABLE);
        return count != null && count > 0;
    }

    private static int probeRowCount(JdbcTemplate jdbc) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM `" + PROBE_TABLE + "`", Integer.class);
        return count == null ? 0 : count;
    }

    private interface SqlWork {
        void run(Connection connection) throws SQLException;
    }

    // ---------------------------------------------------------------- 独立实例准备

    private static String dbUrl(String database) {
        return baseUrl() + database + queryPart();
    }

    private static String baseUrl() {
        String url = guardUrl();
        int query = url.indexOf('?');
        String withoutQuery = query >= 0 ? url.substring(0, query) : url;
        return withoutQuery.substring(0, withoutQuery.lastIndexOf('/') + 1);
    }

    private static String queryPart() {
        String url = guardUrl();
        int query = url.indexOf('?');
        return query >= 0 ? url.substring(query) : "";
    }

    private static String serverUrl() {
        return baseUrl();
    }

    private static String guardUrl() {
        return envOr("IT_GUARD_DATASOURCE_URL", DEFAULT_GUARD_URL);
    }

    private static String username() {
        return envOr("IT_GUARD_DATASOURCE_USERNAME", "root");
    }

    private static String password() {
        return envOr("IT_GUARD_DATASOURCE_PASSWORD", "");
    }

    private static String envOr(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
