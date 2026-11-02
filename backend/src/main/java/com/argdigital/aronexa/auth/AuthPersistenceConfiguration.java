package com.argdigital.aronexa.auth;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Separate organization RLS connection from authentication credential operations.
 * Local dev requires two distinct least-privileged DB accounts.
 * Tests use an ephemeral migrator datasource for fixture setup, and explicitly
 * open auth-role / organization-role connections for privilege verification.
 */
@Configuration(proxyBeanMethods = false)
class AuthPersistenceConfiguration {

    @Bean(name = "applicationJdbcTemplate")
    @Primary
    JdbcTemplate applicationJdbcTemplate(@Qualifier("dataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    @Bean(name = "authJdbcTemplate")
    JdbcTemplate authJdbcTemplate(@Qualifier("authDataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    @Bean(name = "transactionManager")
    @Primary
    PlatformTransactionManager applicationTransactionManager(
            @Qualifier("dataSource") DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }

    @Bean(name = "authTransactionManager")
    PlatformTransactionManager authTransactionManager(
            @Qualifier("authDataSource") DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }

    /**
     * Existing Testcontainers tests share a migrator datasource for fixture
     * loading; role-level tests independently connect using restricted roles.
     * Never activate this test-only alias for normal development.
     */
    @Configuration(proxyBeanMethods = false)
    @Profile("test")
    static class DefaultAuthDataSource {
        // Two datasource definitions suppress Boot's default DataSource creation.
        // Obtain the ephemeral container's real URL/credentials from ServiceConnection.
        @Bean(name = "dataSource", destroyMethod = "close")
        @Primary
        DataSource testDataSource(JdbcConnectionDetails details) {
            HikariConfig config = new HikariConfig();
            config.setPoolName("aronexa-ephemeral-test");
            config.setJdbcUrl(details.getJdbcUrl());
            config.setUsername(details.getUsername());
            config.setPassword(details.getPassword());
            config.setMaximumPoolSize(5);
            return new HikariDataSource(config);
        }

        @Bean(name = "authDataSource")
        DataSource authDataSource(@Qualifier("dataSource") DataSource dataSource) {
            return dataSource;
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Profile("dev")
    static class DevelopmentConnections {
        @Bean(name = "dataSource")
        @Primary
        DataSource applicationDataSource(
                @Value("${spring.datasource.url}") String url,
                @Value("${spring.datasource.username}") String username,
                @Value("${spring.datasource.password}") String password) {
            return pool("aronexa-organization", url, username, password);
        }

        @Bean(name = "authDataSource", destroyMethod = "close")
        DataSource authDataSource(
                @Value("${aronexa.auth.datasource.url}") String url,
                @Value("${aronexa.auth.datasource.username}") String username,
                @Value("${aronexa.auth.datasource.password}") String password) {
            return pool("aronexa-authentication", url, username, password);
        }

        private HikariDataSource pool(String name, String url, String username, String password) {
            HikariConfig config = new HikariConfig();
            config.setPoolName(name);
            config.setJdbcUrl(url);
            config.setUsername(username);
            config.setPassword(password);
            config.setMaximumPoolSize(5);
            config.setConnectionTimeout(5000);
            return new HikariDataSource(config);
        }
    }
}
