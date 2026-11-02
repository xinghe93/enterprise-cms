package com.xinghe.cms.config;

import java.time.LocalDateTime;
import java.util.List;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.xinghe.cms.config.fixture.PersistenceProbe;
import com.xinghe.cms.config.fixture.ProbeMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/** 显式通过 -Dtest=MybatisPlusMysqlIT 运行；仅访问连接内临时表。 */
@SpringBootTest
@Transactional
class MybatisPlusMysqlIT {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ProbeMapper mapper;

    @BeforeEach
    void createConnectionLocalTable() {
        jdbc.execute("""
                CREATE TEMPORARY TABLE IF NOT EXISTS enterprise_cms_config_probe (
                    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
                    display_name VARCHAR(100) NOT NULL,
                    version INT UNSIGNED NOT NULL DEFAULT 0,
                    is_deleted TINYINT UNSIGNED NOT NULL DEFAULT 0,
                    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
                    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                        ON UPDATE CURRENT_TIMESTAMP(3)
                ) ENGINE=InnoDB
                """);
    }

    @Test
    void paginatesInDatabaseAndKeepsOverflowPageEmpty() {
        for (int index = 1; index <= 5; index++) {
            insert("分页" + index);
        }
        Page<PersistenceProbe> page = mapper.selectPage(new Page<>(2, 2),
                new QueryWrapper<PersistenceProbe>().orderByAsc("id"));

        assertThat(page.getTotal()).isEqualTo(5);
        assertThat(page.getRecords()).extracting(PersistenceProbe::getDisplayName)
                .containsExactly("分页3", "分页4");

        Page<PersistenceProbe> overflow = mapper.selectPage(new Page<>(99, 2), null);
        assertThat(overflow.getCurrent()).isEqualTo(99);
        assertThat(overflow.getTotal()).isEqualTo(5);
        assertThat(overflow.getRecords()).isEmpty();
    }

    @Test
    void capsDirectMapperPaginationAtOneHundredRows() {
        for (int index = 1; index <= 105; index++) {
            insert("上限" + index);
        }
        Page<PersistenceProbe> page = mapper.selectPage(new Page<>(1, 1000), null);

        assertThat(page.getSize()).isEqualTo(100);
        assertThat(page.getTotal()).isEqualTo(105);
        assertThat(page.getRecords()).hasSize(100);
    }

    @Test
    void usesAutoIncrementAndDatabaseManagedTimesWithCamelCaseMapping() {
        PersistenceProbe probe = new PersistenceProbe();
        probe.setDisplayName("字段映射");
        LocalDateTime forgedTime = LocalDateTime.of(2000, 1, 1, 0, 0);
        probe.setCreatedAt(forgedTime);
        probe.setUpdatedAt(forgedTime);
        mapper.insert(probe);

        assertThat(probe.getId()).isPositive();
        PersistenceProbe stored = mapper.selectById(probe.getId());
        assertThat(stored.getDisplayName()).isEqualTo("字段映射");
        assertThat(stored.getCreatedAt()).isNotNull().isNotEqualTo(forgedTime);
        assertThat(stored.getUpdatedAt()).isNotNull().isNotEqualTo(forgedTime);
        LocalDateTime originalCreatedAt = stored.getCreatedAt();

        stored.setDisplayName("更新后的字段");
        stored.setCreatedAt(forgedTime);
        stored.setUpdatedAt(forgedTime);
        jdbc.update("UPDATE enterprise_cms_config_probe SET updated_at = ? WHERE id = ?",
                forgedTime, probe.getId());
        assertThat(mapper.updateById(stored)).isEqualTo(1);
        PersistenceProbe updated = mapper.selectById(probe.getId());
        assertThat(updated.getCreatedAt()).isEqualTo(originalCreatedAt);
        assertThat(updated.getUpdatedAt()).isAfter(forgedTime);
    }

    @Test
    void logicalDeleteKeepsPhysicalRowAndHidesItFromQueriesAndXml() {
        PersistenceProbe probe = insert("逻辑删除");

        assertThat(mapper.deleteById(probe.getId())).isEqualTo(1);
        assertThat(mapper.selectById(probe.getId())).isNull();
        assertThat(mapper.selectCount(null)).isZero();
        assertThat(mapper.selectVisibleByName("逻辑删除")).isEmpty();
        assertThat(jdbc.queryForObject(
                "SELECT is_deleted FROM enterprise_cms_config_probe WHERE id = ?",
                Integer.class, probe.getId())).isEqualTo(1);
    }

    @Test
    void staleVersionCannotOverwriteNewerUpdate() {
        PersistenceProbe probe = insert("原始内容");
        PersistenceProbe first = mapper.selectById(probe.getId());
        // 复制旧版本快照，避免 MyBatis 本地缓存让两次读取共享同一个实体对象。
        PersistenceProbe stale = new PersistenceProbe();
        stale.setId(first.getId());
        stale.setDisplayName("过期更新");
        stale.setVersion(first.getVersion());
        first.setDisplayName("已更新内容");

        assertThat(mapper.updateById(first)).isEqualTo(1);
        assertThat(mapper.updateById(stale)).isZero();

        PersistenceProbe current = mapper.selectById(probe.getId());
        assertThat(current.getVersion()).isEqualTo(1);
        assertThat(current.getDisplayName()).isEqualTo("已更新内容");
    }

    @Test
    void moduleXmlBindsValuesWithoutInterpretingThemAsSql() {
        String name = "' OR 1=1 --";
        insert(name);
        insert("其他数据");

        List<PersistenceProbe> selected = mapper.selectVisibleByName(name);

        assertThat(selected).hasSize(1);
        assertThat(selected.getFirst().getDisplayName()).isEqualTo(name);
    }

    private PersistenceProbe insert(String displayName) {
        PersistenceProbe probe = new PersistenceProbe();
        probe.setDisplayName(displayName);
        assertThat(mapper.insert(probe)).isEqualTo(1);
        return probe;
    }
}
