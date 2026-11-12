package com.argdigital.aronexa.patient;

import java.sql.Connection;
import java.sql.DriverManager;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Disposable PostgreSQL V1-V9 only. No production V10 exists in the migration
 * source path; there must be no way to declare the clinic writer ready.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class RegistrationWritePreflightTests {
    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine")
            .withInitScript("db/test/role-bootstrap.sql")
            .withDatabaseName("aronexa_registration_preflight_test")
            .withUsername("aronexa_registration_preflight_test")
            .withPassword("ephemeral-preflight-only");

    private Connection inspector() throws Exception {
        Connection c=DriverManager.getConnection(postgres.getJdbcUrl(),
                postgres.getUsername(), postgres.getPassword());
        // PostgreSQL transactions are read-only; this preflight may never
        // run DDL or touch clinic data.
        c.setReadOnly(true);
        c.setAutoCommit(false);
        return c;
    }

    @Test
    void rejectsNoConnectionOrMissingKeyId() throws Exception {
        assertThat(RegistrationWritePreflight.inspect(null,1).ready()).isFalse();
        try(Connection c=inspector()) {
            var result=RegistrationWritePreflight.inspect(c,0);
            assertThat(result.ready()).isFalse();
            assertThat(result.findings()).containsExactly(
                    RegistrationWritePreflight.Finding.DATABASE_PREFLIGHT_FAILED);
            c.rollback();
        }
    }

    @Test
    void requiresExplicitReadOnlyTransaction() throws Exception {
        try(Connection c=DriverManager.getConnection(postgres.getJdbcUrl(),
                postgres.getUsername(), postgres.getPassword())) {
            var refused=RegistrationWritePreflight.inspect(c,1);
            assertThat(refused.ready()).isFalse();
            assertThat(refused.findings()).containsExactly(
                    RegistrationWritePreflight.Finding.EXPLICIT_READ_ONLY_TRANSACTION_REQUIRED);
        }
    }

    @Test
    void v9DatabaseRemainsBlockedEvenWithSyntheticRestrictedWriterRole() throws Exception {
        try(Connection c=inspector()) {
            var result=RegistrationWritePreflight.inspect(c,1);
            assertThat(result.ready()).isFalse();
            assertThat(result.findings()).contains(
                    RegistrationWritePreflight.Finding.APPROVED_FLYWAY_MIGRATION_MISSING,
                    RegistrationWritePreflight.Finding.REGISTRATION_FUNCTION_MISSING_OR_UNSAFE,
                    RegistrationWritePreflight.Finding.PROOF_KEY_STORE_NOT_READY,
                    RegistrationWritePreflight.Finding.REGISTRATION_AUDIT_NOT_READY);
            assertThat(result.findings()).doesNotContain(
                    RegistrationWritePreflight.Finding.RESTRICTED_WRITER_ROLE_MISSING_OR_UNSAFE,
                    RegistrationWritePreflight.Finding.PATIENT_RLS_NOT_ENFORCED,
                    RegistrationWritePreflight.Finding.PATIENT_RUNTIME_HAS_DIRECT_IDENTITY_WRITE);
            assertThatThrownBy(() -> result.findings().add(RegistrationWritePreflight.Finding.DATABASE_PREFLIGHT_FAILED))
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThat(result.findings())
                    .allSatisfy(finding -> assertThat(finding.name()).matches("[A-Z_]+"));
            c.rollback();
        }
    }

    @Test
    void patientRuntimeDirectInsertIsDetectedEvenWithoutProductionWriter() throws Exception {
        // Testcontainers only: temporary DBA permission change and guaranteed
        // reversal. No production SQL role or real patient records.
        try(Connection admin=DriverManager.getConnection(postgres.getJdbcUrl(),
                postgres.getUsername(),postgres.getPassword())) {
            try {
                try(var statement=admin.createStatement()) {
                    statement.execute("GRANT INSERT ON public.patient TO aronexa_patient_runtime");
                }
                try(Connection c=inspector()) {
                    var result=RegistrationWritePreflight.inspect(c,1);
                    assertThat(result.ready()).isFalse();
                    assertThat(result.findings()).contains(
                            RegistrationWritePreflight.Finding.PATIENT_RUNTIME_HAS_DIRECT_IDENTITY_WRITE);
                    c.rollback();
                }
            } finally {
                try(var statement=admin.createStatement()) {
                    statement.execute("REVOKE INSERT ON public.patient FROM aronexa_patient_runtime");
                }
            }
        }
    }

    @Test
    void neverProvidesSecretValuesOrPatientPayloadInFindings() throws Exception {
        try(Connection c=inspector()) {
            var result=RegistrationWritePreflight.inspect(c,1);
            String text=result.findings().toString();
            assertThat(text).doesNotContain("jdbc:", "password", "PAT-", "SELECT secret");
            assertThat(result.ready()).isFalse();
            c.rollback();
        }
    }
}
