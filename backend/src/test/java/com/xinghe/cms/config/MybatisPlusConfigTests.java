package com.xinghe.cms.config;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.xinghe.cms.config.fixture.NotAMapper;
import com.xinghe.cms.config.fixture.ProbeMapper;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class MybatisPlusConfigTests {

    @Autowired
    private SqlSessionFactory sqlSessionFactory;

    @Autowired
    private ApplicationContext context;

    @Test
    void registersMybatisPlusInterceptors() {
        assertThat(sqlSessionFactory.getConfiguration().getInterceptors())
                .anyMatch(interceptor -> interceptor.getClass().getSimpleName().equals("MybatisPlusInterceptor"));
    }

    @Test
    void scansOnlyAnnotatedMappersAndLoadsModuleXml() {
        assertThat(sqlSessionFactory.getConfiguration().hasMapper(ProbeMapper.class)).isTrue();
        assertThat(context.getBeansOfType(NotAMapper.class)).isEmpty();
        assertThat(sqlSessionFactory.getConfiguration()
                .hasStatement(ProbeMapper.class.getName() + ".selectVisibleByName")).isTrue();
    }

    @Test
    void usesSchemaCompatibleMappingDefaults() {
        var configuration = sqlSessionFactory.getConfiguration();
        var dbConfig = GlobalConfigUtils.getGlobalConfig(configuration).getDbConfig();

        assertThat(configuration.isMapUnderscoreToCamelCase()).isTrue();
        assertThat(dbConfig.getIdType()).isEqualTo(IdType.AUTO);
        assertThat(dbConfig.getLogicDeleteValue()).isEqualTo("1");
        assertThat(dbConfig.getLogicNotDeleteValue()).isEqualTo("0");
        assertThat(dbConfig.getLogicDeleteField()).isNull();
    }
}
