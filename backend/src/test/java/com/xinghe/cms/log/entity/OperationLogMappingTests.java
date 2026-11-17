package com.xinghe.cms.log.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.core.metadata.TableFieldInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.xinghe.cms.log.mapper.OperationLogMapper;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 校验操作日志实体与 sys_operation_log 表的映射约定，不连接 MySQL。
 */
@SpringBootTest
class OperationLogMappingTests {

    @Autowired
    private SqlSessionFactory sqlSessionFactory;

    @Test
    void registersOperationLogMapper() {
        assertThat(sqlSessionFactory.getConfiguration().hasMapper(OperationLogMapper.class)).isTrue();
    }

    @Test
    void mapsTableAndAutoIncrementKey() {
        TableInfo tableInfo = requireTableInfo();

        assertThat(tableInfo.getTableName()).isEqualTo("sys_operation_log");
        assertThat(tableInfo.getKeyProperty()).isEqualTo("id");
        assertThat(tableInfo.getKeyColumn()).isEqualTo("id");
        assertThat(tableInfo.getIdType()).isEqualTo(IdType.AUTO);
    }

    @Test
    void mapsBusinessColumnsWithoutInventingExtraFields() {
        TableInfo tableInfo = requireTableInfo();

        assertThat(tableInfo.getFieldList())
                .extracting(TableFieldInfo::getProperty)
                .containsExactlyInAnyOrder(
                        "userId", "operation", "requestUri", "httpMethod",
                        "result", "durationMs", "createdAt");
        assertThat(tableInfo.getFieldList())
                .extracting(TableFieldInfo::getProperty)
                .doesNotContain("updatedAt", "isDeleted", "version");
    }

    @Test
    void keepsCreatedAtReadOnlyForInsertAndUpdate() {
        TableFieldInfo createdAt = requireTableInfo().getFieldList().stream()
                .filter(field -> "createdAt".equals(field.getProperty()))
                .findFirst()
                .orElseThrow();

        assertThat(createdAt.getColumn()).isEqualTo("created_at");
        assertThat(createdAt.getInsertStrategy()).isEqualTo(FieldStrategy.NEVER);
        assertThat(createdAt.getUpdateStrategy()).isEqualTo(FieldStrategy.NEVER);
    }

    private TableInfo requireTableInfo() {
        TableInfo tableInfo = TableInfoHelper.getTableInfo(OperationLog.class);
        assertThat(tableInfo).as("OperationLog 未被 MyBatis-Plus 初始化").isNotNull();
        return tableInfo;
    }
}
