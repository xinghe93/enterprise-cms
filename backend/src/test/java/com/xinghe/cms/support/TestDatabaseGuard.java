package com.xinghe.cms.support;

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
 */
public final class TestDatabaseGuard {

    private TestDatabaseGuard() {
    }

    /**
     * 校验当前连接的实际库名是否为本次运行分配的独立测试库；不匹配时抛出
     * {@link IllegalStateException}，调用方应在任何建表、插入、删除语句之前调用本方法。
     *
     * @param jdbc             当前测试使用的 JdbcTemplate（必须与后续写入共用同一个连接池）
     * @param expectedDatabase 本次运行分配的独立测试库名，例如 {@code enterprise_cms_it}
     */
    public static void requireDatabase(JdbcTemplate jdbc, String expectedDatabase) {
        if (jdbc == null) {
            throw new IllegalArgumentException("JdbcTemplate 不能为空");
        }
        if (expectedDatabase == null || expectedDatabase.isBlank()) {
            throw new IllegalArgumentException("期望的测试库名不能为空");
        }
        String actualDatabase = jdbc.queryForObject("SELECT DATABASE()", String.class);
        // 精确比较：MySQL 的库名大小写语义依赖实例配置，仅大小写不同也必须拒绝，
        // 避免把数据写进另一个名字相近的库。
        if (!expectedDatabase.equals(actualDatabase)) {
            throw new IllegalStateException("拒绝在非隔离测试库上执行数据库操作：期望 "
                    + expectedDatabase + "，实际连接 " + actualDatabase);
        }
    }
}
