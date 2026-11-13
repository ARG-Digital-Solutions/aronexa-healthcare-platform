package com.argdigital.aronexa.opd;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Explicitly sourced only by the two isolated R1-10B3B Spring application
 * context restarts. Never component-scanned or packaged as src/main.
 *
 * Inputs are an automatically disposable PG17 Testcontainer. No external
 * database configuration, default password, hostname or credential fallback.
 */
@TestConfiguration(proxyBeanMethods=false)
public class OpdDisposableRestartDatabaseConfiguration {
    @Bean
    JdbcConnectionDetails isolatedDisposableJdbcDetails(
            @Value("$"+"{aronexa.testonly.restart.jdbc-url}") String url,
            @Value("$"+"{aronexa.testonly.restart.username}") String username,
            @Value("$"+"{aronexa.testonly.restart.password}") String password) {
        if(!url.startsWith("jdbc:postgresql://")
                || !url.contains("/aronexa_r110b2_disposable_only")
                || !"aronexa_r110b2_temporary".equals(username)
                || password==null || password.isBlank()) {
            throw new IllegalStateException(
                    "Restart fixture is not the disposable R1-10B2 test database");
        }
        return new JdbcConnectionDetails() {
            @Override public String getJdbcUrl(){return url;}
            @Override public String getUsername(){return username;}
            @Override public String getPassword(){return password;}
        };
    }
}
