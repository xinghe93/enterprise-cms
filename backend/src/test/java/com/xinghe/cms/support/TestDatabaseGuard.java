package com.xinghe.cms.support;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 集成测试库保护：在执行任何建表、删表、数据准备或删除之前，校验当前连接的实际库名。
 *
 * <p>Hikari 的 {@code jdbc-url} 等配置可能覆盖测试设置的
 * {@code spring.datasource.url}，导致连接池实际指向业务库；且即使
 * {@code @BeforeEach} 的库名断言失败，JUnit 仍会执行 {@code @AfterEach}
 * 的清理。因此每个真正的数据库操作都必须自己先校验实际库名，不匹配时立即中止、
 * 不执行任何 DDL/DML。</p>
 *
 * <p>比较规则：库名按 MySQL 语义精确比较（区分大小写），仅大小写不同的库名
 * 也必须拒绝；期望库名为空同样拒绝。</p>
 *
 * <p>校验既可以接收 {@link Connection}（调用方随后在<b>同一个连接</b>上执行 SQL），
 * 也可以接收 {@link JdbcTemplate}——后者会通过 {@code execute} 在同一连接上完成校验。</p>
 */
public final class TestDatabaseGuard {

    private TestDatabaseGuard() {
    }

    /**
     * 在指定连接上校验实际库名；调用方应随后复用同一个 {@link Connection} 执行 SQL。
     */
    public static void requireDatabase(Connection connection, String expectedDatabase) {
        if (connection == null) {
            throw new IllegalArgumentException("数据库连接不能为空");
        }
        requireExpectedName(expectedDatabase);
        String actualDatabase;
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT DATABASE()")) {
            actualDatabase = resultSet.next() ? resultSet.getString(1) : null;
        } catch (SQLException exception) {
            throw new IllegalStateException("无法读取当前连接的实际库名", exception);
        }
        rejectMismatch(expectedDatabase, actualDatabase);
    }

    /**
     * 校验 JdbcTemplate 当前连接的实际库名；校验与后续 SQL 共用一次
     * {@link JdbcTemplate#execute(ConnectionCallback)} 的连接。
     */
    public static void requireDatabase(JdbcTemplate jdbc, String expectedDatabase) {
        if (jdbc == null) {
            throw new IllegalArgumentException("JdbcTemplate 不能为空");
        }
        requireExpectedName(expectedDatabase);
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            requireDatabase(connection, expectedDatabase);
            return null;
        });
    }

    private static void requireExpectedName(String expectedDatabase) {
        if (expectedDatabase == null || expectedDatabase.isBlank()) {
            throw new IllegalArgumentException("期望的测试库名不能为空");
        }
    }

    private static void rejectMismatch(String expectedDatabase, String actualDatabase) {
        // 精确比较：MySQL 的库名大小写语义依赖实例配置，仅大小写不同也必须拒绝，
        // 避免把数据写进另一个名字相近的库。
        if (!expectedDatabase.equals(actualDatabase)) {
            throw new IllegalStateException("拒绝在非隔离测试库上执行数据库操作：期望 "
                    + expectedDatabase + "，实际连接 " + actualDatabase);
        }
    }
}
