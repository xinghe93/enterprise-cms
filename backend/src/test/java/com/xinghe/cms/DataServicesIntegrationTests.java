package com.xinghe.cms;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;
import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@EnabledIfEnvironmentVariable(named = "CMS_DATA_INTEGRATION_TESTS", matches = "true")
class DataServicesIntegrationTests {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private RedisConnectionFactory redisConnectionFactory;

    @Test
    void mysqlContainsAllCoreTables() throws Exception {
        Set<String> expected = Set.of(
                "sys_department", "sys_user", "sys_user_role", "sys_role",
                "sys_permission", "sys_role_permission", "cms_category", "cms_tag",
                "cms_article", "cms_review", "cms_article_tag", "sys_operation_log");
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            assertEquals("enterprise_cms", connection.getCatalog());
            Set<String> actual = new HashSet<>();
            try (ResultSet result = statement.executeQuery(
                    "SELECT TABLE_NAME FROM information_schema.TABLES "
                            + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_TYPE = 'BASE TABLE'")) {
                while (result.next()) {
                    actual.add(result.getString(1));
                }
            }
            assertTrue(actual.containsAll(expected), "数据库应包含全部 12 张核心表，实际为：" + actual);
        }
    }

    @Test
    void redisRespondsToPing() {
        try (RedisConnection connection = redisConnectionFactory.getConnection()) {
            assertEquals("PONG", connection.ping());
        }
    }
}
