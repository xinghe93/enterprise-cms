package com.xinghe.cms.support;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PR #28 复审要求的"数据库隔离生命周期"验证：在独立、可丢弃的 MySQL 实例上，
 * 用一次运行专属的探针表走完整生命周期（建表、写入、删表），确认：
 *
 * <ol>
 *   <li>连接指向另一个测试库时，初始化在 <b>任何 DDL/DML 之前</b>失败，
 *       预置的探针表与记录保持原样；失败后的收尾也跳过，不执行任何删除；</li>
 *   <li>连接指向本次运行分配的测试库时，生命周期能完整执行并在收尾时清理干净；</li>
 *   <li>仅大小写不同的库名会被拒绝。</li>
 * </ol>
 *
 * <p>默认连接第二个 MySQL 实例（Docker 容器 {@code mysql8-guard}，端口 {@code 3307}），
 * 可用环境变量覆盖：</p>
 * <ul>
 *   <li>{@code IT_GUARD_DATASOURCE_URL}（默认 {@code jdbc:mysql://127.0.0.1:3307/enterprise_cms_guard?...}）</li>
 *   <li>{@code IT_GUARD_DATASOURCE_USERNAME}（默认 {@code root}）</li>
 *   <li>{@code IT_GUARD_DATASOURCE_PASSWORD}（默认空）</li>
 * </ul>
 *
 * <p>实例不可用时整类跳过，不阻塞常规构建；显式运行：
 * {@code mvn -B -o test "-Dtest=TestDatabaseGuardIT"}。</p>
 */
class TestDatabaseGuardIT {

    /** 被故意配错的测试库（模拟"另一个测试库"）。 */
    private static final String OTHER_TEST_DATABASE = "enterprise_cms_guard";

    /** 本次运行分配的、可丢弃的测试库。 */
    private static final String ASSIGNED_TEST_DATABASE = "enterprise_cms_it";

    private static final String DEFAULT_GUARD_URL =
            "jdbc:mysql://127.0.0.1:3307/" + OTHER_TEST_DATABASE
                    + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&characterEncoding=utf8";

    /** 一次运行专属的探针表名：避免清理碰到其他运行或示例的同名表。 */
    private static final String PROBE_TABLE = "cleanup_probe_" + Long.toHexString(System.nanoTime());

    private JdbcTemplate otherDatabaseJdbc;
    private JdbcTemplate assignedDatabaseJdbc;

    @BeforeEach
    void setUp() {
        assumeGuardInstanceAvailable();
        createDatabase(OTHER_TEST_DATABASE);
        createDatabase(ASSIGNED_TEST_DATABASE);
        otherDatabaseJdbc = jdbcTo(OTHER_TEST_DATABASE);
        assignedDatabaseJdbc = jdbcTo(ASSIGNED_TEST_DATABASE);

        // 预置"另一个测试库"中的探针表与记录：若初始化/收尾越过库名校验，
        // 这张表与这行记录就会被误删，测试即可发现。
        TestDatabaseGuard.requireDatabase(otherDatabaseJdbc, OTHER_TEST_DATABASE);
        otherDatabaseJdbc.execute("DROP TABLE IF EXISTS " + PROBE_TABLE);
        otherDatabaseJdbc.execute("CREATE TABLE " + PROBE_TABLE + " (id BIGINT PRIMARY KEY)");
        otherDatabaseJdbc.update("INSERT INTO " + PROBE_TABLE + " (id) VALUES (1)");
    }

    @AfterEach
    void tearDown() {
        cleanupQuietly(otherDatabaseJdbc);
        cleanupQuietly(assignedDatabaseJdbc);
    }

    @Test
    void failsBeforeAnyDdlWhenConnectedToAnotherTestDatabaseAndKeepsProbeDataOnTeardown() {
        // 该连接实际在 enterprise_cms_guard，但本次运行分配的是 enterprise_cms_it。
        assertThatThrownBy(() -> runGuardedLifecycle(otherDatabaseJdbc, ASSIGNED_TEST_DATABASE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("拒绝在非隔离测试库上执行数据库操作")
                .hasMessageContaining(OTHER_TEST_DATABASE)
                .hasMessageContaining(ASSIGNED_TEST_DATABASE);

        // 初始化失败 → 预置的探针表与记录仍在，且没有被本测试重建。
        assertThat(probeTableExists(otherDatabaseJdbc)).isTrue();
        assertThat(probeRowCount(otherDatabaseJdbc)).isEqualTo(1);

        // 失败后的收尾也必须跳过：再走一次收尾逻辑，仍然不执行任何删除。
        cleanupQuietly(otherDatabaseJdbc);
        assertThat(probeTableExists(otherDatabaseJdbc)).as("失败后收尾不得删除任何表").isTrue();
        assertThat(probeRowCount(otherDatabaseJdbc)).as("失败后收尾不得删除任何记录").isEqualTo(1);
    }

    @Test
    void runsLifecycleAndCleansUpWhenConnectedToAssignedTestDatabase() {
        runGuardedLifecycle(assignedDatabaseJdbc, ASSIGNED_TEST_DATABASE);

        // 生命周期正常走完，收尾已删除本次运行的探针表。
        assertThat(probeTableExists(assignedDatabaseJdbc)).isFalse();

        // 另一个测试库中的预置数据完全未被触碰。
        assertThat(probeTableExists(otherDatabaseJdbc)).isTrue();
        assertThat(probeRowCount(otherDatabaseJdbc)).isEqualTo(1);
    }

    @Test
    void rejectsDatabaseNameThatDiffersOnlyByCase() {
        assertThatThrownBy(() -> TestDatabaseGuard.requireDatabase(
                assignedDatabaseJdbc, ASSIGNED_TEST_DATABASE.toUpperCase()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("拒绝在非隔离测试库上执行数据库操作")
                .hasMessageContaining(ASSIGNED_TEST_DATABASE);
    }

    @Test
    void rejectsBlankExpectedDatabase() {
        assertThatThrownBy(() -> TestDatabaseGuard.requireDatabase(assignedDatabaseJdbc, " "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---------------------------------------------------------------- 被验证的生命周期

    /**
     * 模拟一次测试的完整生命周期：建表前、数据准备前、删表前都先校验实际库名；
     * 收尾校验失败时跳过删除，不触碰任何数据。
     */
    private static void runGuardedLifecycle(JdbcTemplate jdbc, String assignedDatabase) {
        TestDatabaseGuard.requireDatabase(jdbc, assignedDatabase);
        jdbc.execute("DROP TABLE IF EXISTS " + PROBE_TABLE);
        jdbc.execute("CREATE TABLE " + PROBE_TABLE + " (id BIGINT PRIMARY KEY)");

        TestDatabaseGuard.requireDatabase(jdbc, assignedDatabase);
        jdbc.update("INSERT INTO " + PROBE_TABLE + " (id) VALUES (7)");

        cleanupQuietly(jdbc, assignedDatabase);
    }

    /** 收尾：库名校验通过才删表；不匹配时静默跳过，绝不执行删除。 */
    private static void cleanupQuietly(JdbcTemplate jdbc, String assignedDatabase) {
        if (jdbc == null) {
            return;
        }
        try {
            TestDatabaseGuard.requireDatabase(jdbc, assignedDatabase);
            jdbc.execute("DROP TABLE IF EXISTS " + PROBE_TABLE);
        } catch (IllegalStateException skipped) {
            // 库名不匹配：收尾跳过，保留数据，交由人工排查配置。
        }
    }

    private static void cleanupQuietly(JdbcTemplate jdbc) {
        cleanupQuietly(jdbc, ASSIGNED_TEST_DATABASE);
    }

    // ---------------------------------------------------------------- 独立实例准备

    private static void assumeGuardInstanceAvailable() {
        try (Connection ignored = DriverManager.getConnection(serverUrl(), username(), password())) {
            // 能建立到实例的连接即视为可用。
        } catch (SQLException exception) {
            Assumptions.assumeTrue(false, "跳过数据库隔离验证：独立 MySQL 测试实例不可用（"
                    + exception.getMessage() + "）；请启动 Docker 容器 mysql8-guard，或用 "
                    + "IT_GUARD_DATASOURCE_URL 指定独立实例");
        }
    }

    private static void createDatabase(String database) {
        try (Connection connection = DriverManager.getConnection(serverUrl(), username(), password());
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE IF NOT EXISTS " + database
                    + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        } catch (SQLException exception) {
            Assumptions.assumeTrue(false, "跳过数据库隔离验证：无法创建独立测试库 "
                    + database + "（" + exception.getMessage() + "）");
        }
    }

    private static JdbcTemplate jdbcTo(String database) {
        return new JdbcTemplate(new DriverManagerDataSource(
                baseUrl() + database + queryPart(), username(), password()));
    }

    private static boolean probeTableExists(JdbcTemplate jdbc) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables"
                        + " WHERE table_schema = DATABASE() AND table_name = ?",
                Integer.class, PROBE_TABLE);
        return count != null && count > 0;
    }

    private static int probeRowCount(JdbcTemplate jdbc) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM " + PROBE_TABLE, Integer.class);
        return count == null ? 0 : count;
    }

    private static String baseUrl() {
        String url = guardUrl();
        int query = url.indexOf('?');
        if (query >= 0) {
            url = url.substring(0, query);
        }
        return url.substring(0, url.lastIndexOf('/') + 1);
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
