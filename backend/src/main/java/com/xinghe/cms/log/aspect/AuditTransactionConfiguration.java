package com.xinghe.cms.log.aspect;

import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.transaction.autoconfigure.TransactionManagerCustomizers;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.support.JdbcTransactionManager;

@Configuration(proxyBeanMethods = false)
class AuditTransactionConfiguration {

    @Bean
    JdbcTransactionManager transactionManager(DataSource dataSource,
            @Value("${spring.dao.exceptiontranslation.enabled:true}") boolean translateSqlExceptions,
            ObjectProvider<TransactionManagerCustomizers> customizers) {
        var manager = new AuditJdbcTransactionManager(dataSource, translateSqlExceptions);
        customizers.ifAvailable(value -> value.customize(manager));
        return manager;
    }
}
