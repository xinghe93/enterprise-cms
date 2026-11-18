package com.xinghe.cms.log.mapper;

import java.time.LocalDateTime;

import com.xinghe.cms.log.vo.OperationLogVO;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 校验日志查询 XML 已加载，以及 VO 的 ID/时间序列化契约；不连接 MySQL。
 */
@SpringBootTest
class OperationLogMapperXmlTests {

    @Autowired
    private SqlSessionFactory sqlSessionFactory;

    @Autowired
    private JsonMapper jsonMapper;

    @Test
    void loadsLogQueryStatements() {
        String namespace = OperationLogMapper.class.getName();

        assertThat(sqlSessionFactory.getConfiguration().hasStatement(namespace + ".selectLogPage")).isTrue();
        assertThat(sqlSessionFactory.getConfiguration().hasStatement(namespace + ".selectLogDetail")).isTrue();
    }

    @Test
    void serializesBusinessIdsAsStringsAndCreatedAtWithMillis() {
        OperationLogVO vo = new OperationLogVO();
        vo.setId(123L);
        vo.setUserId(9L);
        vo.setUserName("审核员");
        vo.setCreatedAt(LocalDateTime.of(2026, 11, 2, 16, 0, 0, 120_000_000));

        JsonNode json = jsonMapper.readTree(jsonMapper.writeValueAsString(vo));

        assertThat(json.get("id").isString()).isTrue();
        assertThat(json.get("id").asString()).isEqualTo("123");
        assertThat(json.get("userId").isString()).isTrue();
        assertThat(json.get("userId").asString()).isEqualTo("9");
        assertThat(json.get("createdAt").asString()).isEqualTo("2026-11-02T16:00:00.120");
    }

    @Test
    void serializesNullBusinessIdsAndCreatedAtAsNull() {
        OperationLogVO vo = new OperationLogVO();

        JsonNode json = jsonMapper.readTree(jsonMapper.writeValueAsString(vo));

        assertThat(json.get("id").isNull()).isTrue();
        assertThat(json.get("userId").isNull()).isTrue();
        assertThat(json.get("createdAt").isNull()).isTrue();
    }
}
