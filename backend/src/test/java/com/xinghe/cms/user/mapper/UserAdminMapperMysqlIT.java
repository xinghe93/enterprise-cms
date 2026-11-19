package com.xinghe.cms.user.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.xinghe.cms.user.mapper.row.UserStatusSnapshot;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * 真实 MySQL 上验证状态快照的构造器映射与版本递增（PR #39 审查 P1 要求
 * 「用真实 Mapper 验证两个入口」：启停与角色全量替换都经 {@code findStatusForUpdate}
 * / {@code updateStatus} / {@code findStatusVersion}）。
 *
 * <p>背景：statusSnapshotMap 曾把 status 声明为 {@code java.lang.Integer}，与
 * {@code UserStatusSnapshot} 的基本类型 {@code (int, long)} 构造器不匹配——
 * MyBatis 按声明类型精确查找构造器，真实查询时直接抛映射异常，启停与角色全量替换
 * 全部 500；Mock 单测打桩了 Mapper，发现不了这类映射缺陷。</p>
 *
 * <p>命名以 {@code IT} 结尾，默认不被 surefire 收集，需显式执行：
 * {@code ./mvnw test -Dtest=UserAdminMapperMysqlIT}；需要可连接的 MySQL
 * （默认 {@code 127.0.0.1:3306/enterprise_cms}，须先执行 004 迁移脚本）。
 * 数据写在事务内，测试结束回滚。</p>
 */
@SpringBootTest
@Transactional
class UserAdminMapperMysqlIT {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private UserAdminMapper mapper;

    /** 显式记录插入的主键；即使测试事务配置异常，也在 @AfterEach 中精确清理这些行。 */
    private final List<Long> insertedIds = new ArrayList<>();

    @AfterEach
    void cleanUpInsertedUsers() {
        for (Long id : insertedIds) {
            jdbc.update("DELETE FROM sys_user WHERE id = ?", id);
        }
        insertedIds.clear();
    }

    private long insertUser(String username, int status) {
        jdbc.update("""
                INSERT INTO sys_user (username, password_hash, real_name, status)
                VALUES (?, 'it-hash', '映射验证', ?)
                """, username, status);
        Long id = jdbc.queryForObject(
                "SELECT id FROM sys_user WHERE username = ?", Long.class, username);
        assertThat(id).isNotNull();
        insertedIds.add(id);
        return id;
    }

    /** findStatusForUpdate 的构造器映射：启停与角色全量替换共用的加锁入口。 */
    @Test
    void statusSnapshotMapsPrimitiveConstructorColumns() {
        long id = insertUser("it_snapshot_probe", 1);

        UserStatusSnapshot snapshot = mapper.findStatusForUpdate(id);

        assertThat(snapshot).isNotNull();
        assertThat(snapshot.status()).isEqualTo(1);
        assertThat(snapshot.statusVersion()).isZero();
    }

    /**
     * updateStatus 每次真实状态变化 +1；资料更新不抬升撤销版本（004 迁移脚本语义）。
     * 注意Mapper 的 SQL 不判断同状态——「重复设置同一状态不写库」由 UserStatusService
     * 的同状态短路保证，并由 UserStatusServiceTests 覆盖，避免把业务语义错放进 SQL。
     */
    @Test
    void updateStatusBumpsIndependentVersionOncePerChange() {
        long id = insertUser("it_status_version_probe", 1);

        assertThat(mapper.updateStatus(id, 0)).isEqualTo(1);
        UserStatusSnapshot afterDisable = mapper.findStatusVersion(id);
        assertThat(afterDisable.status()).isZero();
        assertThat(afterDisable.statusVersion()).isEqualTo(1L);

        jdbc.update("UPDATE sys_user SET real_name = '改名' WHERE id = ?", id);
        UserStatusSnapshot afterRename = mapper.findStatusVersion(id);
        assertThat(afterRename.statusVersion()).isEqualTo(1L);

        assertThat(mapper.updateStatus(id, 1)).isEqualTo(1);
        UserStatusSnapshot afterEnable = mapper.findStatusVersion(id);
        assertThat(afterEnable.status()).isEqualTo(1);
        assertThat(afterEnable.statusVersion()).isEqualTo(2L);
    }
}
