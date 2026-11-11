package com.argdigital.aronexa.patient;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * C2-S2-C14 synthetic-only server log configuration probe.
 *
 * This proves only that bind parameter VALUES are suppressed by a specific
 * disposable PostgreSQL 17 configuration, even with statement/error logging.
 * It does not prove that SQL literals, database ERROR text, JDBC/APM logs or
 * production deployments will redact clinical data.
 *
 * Never print the canary or the full PostgreSQL log in failures or CI output.
 */
@Testcontainers
class SyntheticPostgresLogRedactionAcceptanceTests {
    @Container
    static PostgreSQLContainer postgres =
            new PostgreSQLContainer("postgres:17-alpine")
                    .withDatabaseName("aronexa_c14_log_isolated")
                    .withUsername("aronexa_c14_log_isolated")
                    .withPassword("ephemeral-log-probe-only")
                    .withCommand("postgres",
                            "-c", "log_statement=all",
                            "-c", "log_parameter_max_length=0",
                            "-c", "log_parameter_max_length_on_error=0",
                            "-c", "log_min_error_statement=error");

    @Test
    void preparedStatementBoundValuesStayOutOfConfiguredPostgresLogs() throws Exception {
        // Markers are random, disposable and unrelated to any actual patient.
        String syntheticCanary="SyntheticOnlyC14"+UUID.randomUUID().toString().replace("-","");
        try (Connection connection=DriverManager.getConnection(postgres.getJdbcUrl(),
                postgres.getUsername(),postgres.getPassword())) {
            try(var settings=connection.createStatement()) {
                assertThat(setting(settings,"log_statement")).isEqualTo("all");
                assertThat(setting(settings,"log_parameter_max_length")).isEqualTo("0");
                assertThat(setting(settings,"log_parameter_max_length_on_error")).isEqualTo("0");
                assertThat(setting(settings,"log_min_error_statement")).isEqualTo("error");
            }
            try(var success=connection.prepareStatement(
                    "SELECT ?::text AS c14_statement_probe")) {
                success.setString(1,syntheticCanary);
                try(var row=success.executeQuery()) {
                    assertThat(row.next()).isTrue();
                    assertThat(row.getString(1)).isEqualTo(syntheticCanary);
                }
            }
            try(var failing=connection.prepareStatement(
                    "SELECT ?::text AS c14_error_probe, 1 / (?::int - ?::int)")) {
                failing.setString(1,syntheticCanary);
                failing.setInt(2,7);
                failing.setInt(3,7);
                assertThatThrownBy(failing::executeQuery)
                        .isInstanceOf(SQLException.class)
                        .matches(e -> "22012".equals(((SQLException)e).getSQLState()),
                                "synthetic division by zero must exercise an SQL error log");
            }
        }

        // Require observable statement and error SQL *text*, or the test
        // would give a false green result without exercising server logging.
        boolean observed=false;
        boolean safe=false;
        for(int attempt=0;attempt<25;attempt++) {
            String logs=postgres.getLogs();
            observed=logs.contains("c14_statement_probe")
                    && logs.contains("c14_error_probe");
            safe=!logs.contains(syntheticCanary);
            if(observed && !safe) break;
            if(observed) break;
            Thread.sleep(100);
        }
        assertThat(observed).as("C14_INCONCLUSIVE: SQL statement/error logs not observed").isTrue();
        assertThat(safe).as("C14_BLOCKED: a bound synthetic value was found in PostgreSQL logs")
                .isTrue();
    }

    private static String setting(java.sql.Statement query,String name) throws SQLException {
        try(var result=query.executeQuery("SHOW "+name)) {
            assertThat(result.next()).isTrue();
            return result.getString(1);
        }
    }
}
