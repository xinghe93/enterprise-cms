package com.xinghe.cms.review.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableFieldInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 校验 ReviewRecord 与 cms_review 表的 ORM 映射；不启动 Spring、不连数据库。
 */
class ReviewRecordMappingTests {

    private static TableInfo tableInfo;

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        tableInfo = TableInfoHelper.initTableInfo(assistant, ReviewRecord.class);
    }

    @Test
    void mapsToReviewTableWithAutoIncrementId() {
        assertThat(tableInfo.getTableName()).isEqualTo("cms_review");
        assertThat(tableInfo.getIdType()).isEqualTo(IdType.AUTO);
        assertThat(tableInfo.getKeyProperty()).isEqualTo("id");
        assertThat(tableInfo.getKeyColumn()).isEqualTo("id");
    }

    @Test
    void declaresOnlyReviewColumns() {
        Set<String> columns = tableInfo.getFieldList().stream()
                .map(TableFieldInfo::getColumn)
                .collect(Collectors.toSet());

        assertThat(columns).containsExactlyInAnyOrder(
                "article_id", "reviewer_id", "result", "comment", "reviewed_at");
    }

    @Test
    void reviewedAtIsGeneratedByDatabase() {
        TableFieldInfo reviewedAt = fieldInfo("reviewedAt");

        assertThat(reviewedAt.getColumn()).isEqualTo("reviewed_at");
        assertThat(reviewedAt.getInsertStrategy()).isEqualTo(FieldStrategy.NEVER);
        assertThat(reviewedAt.getUpdateStrategy()).isEqualTo(FieldStrategy.NEVER);
    }

    private static TableFieldInfo fieldInfo(String property) {
        return tableInfo.getFieldList().stream()
                .filter(field -> property.equals(field.getProperty()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("缺少字段：" + property));
    }
}
