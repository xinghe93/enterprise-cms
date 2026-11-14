package com.xinghe.cms.support;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 集成测试库保护：在执行任何清理/删除之前校验当前连接的实际库名。
 *
 * <p>Hikari 的 {@code jdbc-url} 等配置可能覆盖测试设置的
 * {@code spring.datasource.url}，导致连接池实际指向业务库；且即使
 * {@code @BeforeEach} 的库名断言失败，JUnit 仍会执行 {@code @AfterEach}
 * 的清理。因此清理逻辑必须自己先校验实际库名，不匹配时立即中止、不执行任何删除。</p>
 */
public final class TestDatabaseGuard {

    private TestDatabaseGuard() {
    }

    /**
     * 校验当前连接的实际库名是否为独立测试库；不匹配时抛出
     * {@link IllegalStateException}，调用方应在任何删除语句之前调用本方法。
     *
     * @param jdbc             当前测试使用的 JdbcTemplate
     * @param expectedDatabase 期望的独立测试库名，例如 {@code enterprise_cms_it}
     */
    public static void requireDatabase(JdbcTemplate jdbc, String expectedDatabase) {
        String actualDatabase = jdbc.queryForObject("SELECT DATABASE()", String.class);
        if (actualDatabase == null || !actualDatabase.equalsIgnoreCase(expectedDatabase)) {
            throw new IllegalStateException("拒绝在非隔离测试库上执行数据清理：期望 "
                    + expectedDatabase + "，实际连接 " + actualDatabase);
        }
    }
}
