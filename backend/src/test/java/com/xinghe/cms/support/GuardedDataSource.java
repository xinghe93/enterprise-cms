package com.xinghe.cms.support;

import java.sql.Connection;
import java.sql.SQLException;

import javax.sql.DataSource;

import org.springframework.jdbc.datasource.DelegatingDataSource;

/**
 * 带库名校验的数据源包装：每次向底层连接池借用连接时，先在<b>该连接本身</b>上执行
 * {@code SELECT DATABASE()}，实际库名与本次运行分配的独立测试库不一致就关闭连接并抛错。
 *
 * <p>这样校验与实际执行 DDL/DML 的是同一个物理连接，不存在"校验用一条连接、写入用另一条连接"
 * 的窗口；Hikari 的 {@code jdbc-url} 覆盖 {@code spring.datasource.url} 之类的配置错误
 * 也会在第一次取连接时立即暴露。</p>
 */
public final class GuardedDataSource extends DelegatingDataSource {

    private final String expectedDatabase;

    public GuardedDataSource(DataSource targetDataSource, String expectedDatabase) {
        super(targetDataSource);
        if (expectedDatabase == null || expectedDatabase.isBlank()) {
            throw new IllegalArgumentException("期望的测试库名不能为空");
        }
        this.expectedDatabase = expectedDatabase;
    }

    @Override
    public Connection getConnection() throws SQLException {
        return guard(super.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return guard(super.getConnection(username, password));
    }

    private Connection guard(Connection connection) throws SQLException {
        try {
            TestDatabaseGuard.requireDatabase(connection, expectedDatabase);
            return connection;
        } catch (RuntimeException rejected) {
            try {
                connection.close();
            } catch (SQLException ignored) {
                // 关闭失败不覆盖原始拒绝原因。
            }
            throw new SQLException(rejected.getMessage(), rejected);
        }
    }
}
