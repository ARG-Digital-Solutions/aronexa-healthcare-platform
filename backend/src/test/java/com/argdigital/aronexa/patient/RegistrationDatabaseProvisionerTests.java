package com.argdigital.aronexa.patient;

import java.sql.Connection;
import java.sql.DriverManager;
import java.security.SecureRandom;
import java.util.Arrays;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.assertj.core.api.Assertions.*;

/** Real PostgreSQL installer operations, confined to a fresh disposable container. */
@Testcontainers
class RegistrationDatabaseProvisionerTests {
    @Container static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine")
            .withInitScript("db/test/role-bootstrap.sql");

    static Connection connect() throws Exception {
        return DriverManager.getConnection(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword());
    }
    static byte[] key() { byte[] key=new byte[32];new SecureRandom().nextBytes(key);return key; }
    @BeforeAll static void install() throws Exception {
        try(var c=connect()) {
            // Bootstrap has created an unused writer role; exercise creation from
            // absence BEFORE installing any table/function dependencies.
            c.createStatement().execute("DROP ROLE aronexa_registration_writer");
            c.setAutoCommit(false);
            assertThat(RegistrationDatabaseProvisioner.ensureRestrictedWriter(c)).isTrue();
            c.commit();
        }
        Flyway.configure().dataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword())
                .locations("classpath:db/migration","classpath:db/proposed").load().migrate();
    }
    @Test void repeatRoleSetupPreservesRestrictedRole() throws Exception {
        try(var c=connect()) {
            c.setAutoCommit(false);
            assertThat(RegistrationDatabaseProvisioner.ensureRestrictedWriter(c)).isFalse();
            c.rollback();
        }
    }
    @Test void refusesAutocommitToKeepProvisioningAtomic() throws Exception {
        try(var c=connect()) {
            assertThatThrownBy(()->RegistrationDatabaseProvisioner.ensureRestrictedWriter(c))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(()->RegistrationDatabaseProvisioner.installProofKey(c,20,key()))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
    @Test void unsafeExistingRoleIsRejectedWithoutAutomaticRepair() throws Exception {
        try(var c=connect()) {
            c.setAutoCommit(false);
            c.createStatement().execute("ALTER ROLE aronexa_registration_writer LOGIN");
            assertThatThrownBy(()->RegistrationDatabaseProvisioner.ensureRestrictedWriter(c))
                    .isInstanceOf(IllegalStateException.class);
            c.rollback();
        }
    }
    @Test void roleMembershipInEitherDirectionIsRejected() throws Exception {
        for(String grant:new String[]{"GRANT aronexa_registration_writer TO aronexa_patient_runtime",
                "GRANT aronexa_patient_runtime TO aronexa_registration_writer"}) {
            try(var c=connect()) {
                c.setAutoCommit(false);c.createStatement().execute(grant);
                assertThatThrownBy(()->RegistrationDatabaseProvisioner.ensureRestrictedWriter(c))
                        .isInstanceOf(IllegalStateException.class);
                c.rollback();
            }
        }
    }
    @Test void sameKeyRerunDoesNotReplaceOrDuplicateSecret() throws Exception {
        byte[] material=key();
        try(var c=connect()) {
            c.setAutoCommit(false);
            assertThat(RegistrationDatabaseProvisioner.installProofKey(c,21,material)).isTrue();
            assertThat(RegistrationDatabaseProvisioner.installProofKey(c,21,material)).isFalse();
            try(var r=c.createStatement().executeQuery("SELECT count(*) FROM patient_registration_proof_key WHERE id=21")) {
                r.next();assertThat(r.getInt(1)).isEqualTo(1);
            }
            c.rollback();
        }
    }
    @Test void changedKeyIsRefusedAndOriginalIsPreserved() throws Exception {
        byte[] original=key(),changed=original.clone();changed[0]^=1;
        try(var c=connect()) {
            c.setAutoCommit(false);RegistrationDatabaseProvisioner.installProofKey(c,22,original);
            assertThatThrownBy(()->RegistrationDatabaseProvisioner.installProofKey(c,22,changed))
                    .isInstanceOf(IllegalStateException.class).hasMessage("REGISTRATION_KEY_CONFLICT");
            try(var r=c.createStatement().executeQuery("SELECT secret FROM patient_registration_proof_key WHERE id=22")) {
                r.next();assertThat(Arrays.equals(original,r.getBytes(1))).isTrue();
            }
            c.rollback();
        }
    }
    @Test void disabledOrExpiredKeysCannotBeReactivatedBySetup() throws Exception {
        for(String change:new String[]{"enabled=false","valid_until=now()-interval '1 second'"}) {
            try(var c=connect()) {
                c.setAutoCommit(false);byte[] material=key();
                RegistrationDatabaseProvisioner.installProofKey(c,23,material);
                c.createStatement().execute("UPDATE patient_registration_proof_key SET "+change+" WHERE id=23");
                assertThatThrownBy(()->RegistrationDatabaseProvisioner.installProofKey(c,23,material))
                        .isInstanceOf(IllegalStateException.class).hasMessage("REGISTRATION_KEY_CONFLICT");
                c.rollback();
            }
        }
    }
    @Test void invalidKeysAreRejectedBeforeDatabaseWrites() throws Exception {
        try(var c=connect()) {
            c.setAutoCommit(false);
            for(byte[] material:new byte[][]{null,new byte[0],new byte[31],new byte[33]}) {
                assertThatThrownBy(()->RegistrationDatabaseProvisioner.installProofKey(c,24,material))
                        .isInstanceOf(IllegalArgumentException.class);
            }
            assertThatThrownBy(()->RegistrationDatabaseProvisioner.installProofKey(c,0,key()))
                    .isInstanceOf(IllegalArgumentException.class);
            c.rollback();
        }
    }
    @Test void rollbackLeavesNoKeyInAnotherConnection() throws Exception {
        try(var c=connect()) {
            c.setAutoCommit(false);RegistrationDatabaseProvisioner.installProofKey(c,25,key());c.rollback();
        }
        try(var c=connect();var r=c.createStatement().executeQuery("SELECT count(*) FROM patient_registration_proof_key WHERE id=25")) {
            r.next();assertThat(r.getInt(1)).isZero();
        }
    }
}
