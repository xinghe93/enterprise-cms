package com.xinghe.cms.config;

import com.mysql.cj.conf.ConnectionUrl;
import com.mysql.cj.conf.DefaultPropertySet;
import com.mysql.cj.conf.PropertyKey;
import com.zaxxer.hikari.HikariDataSource;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Properties;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class DataSourceTimezoneConfigTests {

    @Autowired
    private DataSource dataSource;

    @Test
    void hikariDataSourceEnforcesUtcPlus8SessionTimeZone() {
        assertThat(dataSource).isInstanceOf(HikariDataSource.class);
        HikariDataSource hikari = (HikariDataSource) dataSource;
        Properties props = hikari.getDataSourceProperties();

        assertThat(props.getProperty("connectionTimeZone")).isEqualTo("+08:00");
        assertThat(props.getProperty("forceConnectionTimeZoneToSession")).isEqualTo("true");
        // Verify +08:00 normalizes to ZoneOffset so Connector/J sends SET SESSION time_zone='+08:00'
        // without depending on mysql.time_zone_name tables.
        assertThat(ZoneId.of(props.getProperty("connectionTimeZone")).normalized())
                .isEqualTo(ZoneOffset.of("+08:00"));
    }

    @Test
    void sessionTimeZoneAppliesEvenWhenDatasourceUrlIsOverriddenWithoutQueryParams() {
        HikariDataSource hikari = (HikariDataSource) dataSource;
        Properties props = hikari.getDataSourceProperties();

        ConnectionUrl plainUrl = ConnectionUrl.getConnectionUrlInstance(
                "jdbc:mysql://127.0.0.1:3306/enterprise_cms_custom", props);
        DefaultPropertySet plainSet = new DefaultPropertySet();
        plainSet.initializeProperties(plainUrl.getMainHost().exposeAsProperties());

        assertThat(plainSet.getStringProperty(PropertyKey.connectionTimeZone).getValue())
                .isEqualTo("+08:00");
        assertThat(plainSet.getBooleanProperty(PropertyKey.forceConnectionTimeZoneToSession).getValue())
                .isTrue();
    }

    @Test
    void sessionTimeZoneRemainsValidWhenUrlAlsoSpecifiesLegacyServerTimezone() {
        HikariDataSource hikari = (HikariDataSource) dataSource;
        Properties props = hikari.getDataSourceProperties();

        ConnectionUrl legacyUrl = ConnectionUrl.getConnectionUrlInstance(
                "jdbc:mysql://127.0.0.1:3306/enterprise_cms_it?useUnicode=true&serverTimezone=Asia/Shanghai", props);
        DefaultPropertySet legacySet = new DefaultPropertySet();
        legacySet.initializeProperties(legacyUrl.getMainHost().exposeAsProperties());

        assertThat(legacySet.getStringProperty(PropertyKey.connectionTimeZone).getValue())
                .isEqualTo("+08:00");
        assertThat(legacySet.getBooleanProperty(PropertyKey.forceConnectionTimeZoneToSession).getValue())
                .isTrue();
    }
}
