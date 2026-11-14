package com.xinghe.cms.support;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PR #28 复审要求的"清理保护"验证：在独立的 MySQL 测试实例上确认
 * 库名不匹配时 <b>不会执行任何删除</b>。
 *
 * <p>默认连接第二个 MySQL 实例（Docker 容器 {@code mysql8-guard}，端口 {@code 3307}），
 * 可用环境变量覆盖：</p>
 * <ul>
 *   <li>{@code IT_GUARD_DATASOURCE_URL}（默认 {@code jdbc:mysql://127.0.0.1:3307/enterprise_cms_guard?...}）</li>
 *   <li>{@code IT_GUARD_DATASOURCE_USERNAME}（默认 {@code root}）</li>
 *   <li>{@code IT_GUARD_DATASOURCE_PASSWORD}（默认空）</li>
 * </ul>
 *
 * <p>显式运行：{@code mvn -B -o test "-Dtest=TestDatabaseGuardIT"}；
 * 类名以 IT 结尾，默认 surefire 不包含。</p>
 */
class TestDatabaseGuardIT {

    private static final String GUARD_DATABASE = "enterprise_cms_guard";
    private static final String EXPECTED_IT_DATABASE = "enterprise_cms_it";
    private static final String DEFAULT_GUARD_URL =
            "jdbc:mysql://127.0.0.1:3307/" + GUARD_DATABASE
                    + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&characterEncoding=utf8";

    private JdbcTemplate guardJdbc;

    /**
     * 第二个 MySQL 实例仅用于本类验证；常规全量测试环境通常没有它，
     * 因此连接失败时用 JUnit 假设直接跳过，而不是让全量构建失败。
     */
    @BeforeAll
    static void provisionGuardDatabase() {
        try {
            try (Connection connection = DriverManager.getConnection(serverUrl(),
                    envOr("IT_GUARD_DATASOURCE_USERNAME", "root"), envOr("IT_GUARD_DATASOURCE_PASSWORD", ""))) {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("CREATE DATABASE IF NOT EXISTS " + GUARD_DATABASE
                            + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
                }
            }
        } catch (SQLException exception) {
            Assumptions.assumeTrue(false, "跳过清理保护验证：独立 MySQL 测试实例不可用（"
                    + exception.getMessage() + "）；显式运行请先启动 Docker 容器 mysql8-guard 或用 "
                    + "IT_GUARD_DATASOURCE_URL 指定");
        }
    }

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                guardUrl(), envOr("IT_GUARD_DATASOURCE_USERNAME", "root"),
                envOr("IT_GUARD_DATASOURCE_PASSWORD", ""));
        guardJdbc = new JdbcTemplate(dataSource);
        guardJdbc.execute("DROP TABLE IF EXISTS cleanup_probe");
        guardJdbc.execute("CREATE TABLE cleanup_probe (id BIGINT PRIMARY KEY)");
        guardJdbc.update("INSERT INTO cleanup_probe (id) VALUES (1)");
    }

    @AfterEach
    void tearDown() {
        guardJdbc.execute("DROP TABLE IF EXISTS cleanup_probe");
    }

    @Test
    void skipsAllDeletesWhenConnectedDatabaseIsNotTheIsolatedTestDatabase() {
        Runnable guardedCleanup = () -> {
            TestDatabaseGuard.requireDatabase(guardJdbc, EXPECTED_IT_DATABASE);
            guardJdbc.update("DELETE FROM cleanup_probe");
        };

        assertThatThrownBy(guardedCleanup::run)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("拒绝在非隔离测试库上执行数据清理")
                .hasMessageContaining(GUARD_DATABASE);

        assertThat(guardJdbc.queryForObject("SELECT COUNT(*) FROM cleanup_probe", Integer.class))
                .as("库名不匹配时不得执行任何删除")
                .isEqualTo(1);
        assertThat(guardJdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo(GUARD_DATABASE);
    }

    @Test
    void executesCleanupOnlyWhenConnectedDatabaseMatches() {
        Runnable guardedCleanup = () -> {
            TestDatabaseGuard.requireDatabase(guardJdbc, GUARD_DATABASE);
            guardJdbc.update("DELETE FROM cleanup_probe");
        };

        guardedCleanup.run();

        assertThat(guardJdbc.queryForObject("SELECT COUNT(*) FROM cleanup_probe", Integer.class)).isZero();
    }

    // ---------------------------------------------------------------- 独立实例准备

    private static String guardUrl() {
        return envOr("IT_GUARD_DATASOURCE_URL", DEFAULT_GUARD_URL);
    }

    private static String serverUrl() {
        String url = guardUrl();
        int query = url.indexOf('?');
        if (query >= 0) {
            url = url.substring(0, query);
        }
        return url.substring(0, url.lastIndexOf('/') + 1);
    }

    private static String envOr(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
