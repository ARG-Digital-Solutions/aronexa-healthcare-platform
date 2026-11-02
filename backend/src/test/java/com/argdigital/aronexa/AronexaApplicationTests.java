package com.argdigital.aronexa;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.flywaydb.core.Flyway;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P01-T02: Tests run only against an ephemeral real PostgreSQL container.
 * Testcontainers must fail the build if Docker/PostgreSQL are unavailable.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class AronexaApplicationTests {
    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine")
            .withInitScript("db/test/role-bootstrap.sql")
            .withDatabaseName("aronexa_test")
            .withUsername("aronexa_test")
            .withPassword("ephemeral-test-only");

    @Autowired
    Flyway flyway;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void contextLoadsAndConnectsToEphemeralTestDatabase() {
        assertThat(jdbc.queryForObject("select current_database()", String.class))
                .isEqualTo("aronexa_test");
    }

    @Test
    void freshDatabaseIsMigratedAndMigrationIsValidated() {
        assertThat(flyway.info().current()).isNotNull();
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("5");
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
        assertThat(jdbc.queryForObject(
                "select count(*) from information_schema.tables " +
                "where table_schema = 'public' and table_name = 'platform_schema_metadata'",
                Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select count(*) from information_schema.tables " +
                "where table_schema = 'public' and table_name in ('tenant','facility','branch','department')",
                Integer.class)).isEqualTo(4);
        assertThat(jdbc.queryForObject(
                "select count(*) from information_schema.tables " +
                "where table_schema = 'public' and table_name in ('app_user','user_tenant_membership', 'auth_role', 'auth_permission', 'auth_session', 'auth_audit_event')",
                Integer.class)).isEqualTo(6);
    }

    @Test
    void destructiveFlywayCleanIsDisabled() {
        assertThat(flyway.getConfiguration().isCleanDisabled()).isTrue();
    }

    @Test
    void repeatMigrationDoesNotReapplyVersionedSql() {
        int migrationsBefore = flyway.info().applied().length;
        flyway.migrate();
        assertThat(flyway.info().applied()).hasSize(migrationsBefore);
    }
}
