package com.argdigital.aronexa.encounter;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T07: disposable-only persistence/RLS proof. NO clinical HTTP API, production
 * Flyway migration or privileges are granted by this test.
 * Transaction-local GUCs are not tamper-proof against a stolen SQL role:
 * production must enforce authenticated session + current grants separately.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EncounterDraftPostgresProofTests {
    private static final String TEST_PASSWORD = "only-for-disposable-encounter-test";

    @Container @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine")
            .withInitScript("db/test/role-bootstrap.sql")
            .withDatabaseName("aronexa_encounter_disposable_test")
            .withUsername("aronexa_encounter_disposable_test")
            .withPassword("ephemeral-migrator-only");

    @Autowired JdbcTemplate admin;

    @BeforeAll
    void createDisposableSchemaAfterFlyway() throws Exception {
        try (Connection c = admin.getDataSource().getConnection()) {
            ScriptUtils.executeSqlScript(c, new ClassPathResource("db/test/encounter-proof.sql"));
        }
    }

    record Fixture(UUID tenantA, UUID tenantB,
                   UUID branchA, UUID otherBranchA, UUID branchB,
                   UUID doctorA, UUID otherDoctorA, UUID doctorB,
                   UUID patientA, UUID patientB,
                   UUID encounterA, UUID otherBranchEncounterA,
                   UUID otherDoctorEncounterA, UUID encounterB) {}

    private UUID tenant() {
        UUID id = UUID.randomUUID();
        admin.update("INSERT INTO public.tenant(id,code,display_name) VALUES(?,?,?)",
                id, "enc_" + id.toString().substring(0, 10), "Synthetic tenant");
        return id;
    }

    private UUID branch(UUID tenantId) {
        UUID facilityId = UUID.randomUUID(), branchId = UUID.randomUUID();
        admin.update("""
                INSERT INTO public.facility(id,tenant_id,code,display_name,facility_kind)
                VALUES(?,?,?,'Fictional clinic','CLINIC')
                """, facilityId, tenantId, "fac_" + facilityId.toString().substring(0, 8));
        admin.update("""
                INSERT INTO public.branch(id,tenant_id,facility_id,code,display_name)
                VALUES(?,?,?,?,'Fictional branch')
                """, branchId, tenantId, facilityId, "branch_" + branchId.toString().substring(0, 8));
        return branchId;
    }

    private UUID doctor(UUID tenantId) {
        UUID userId = UUID.randomUUID(), membershipId = UUID.randomUUID();
        admin.update("INSERT INTO public.app_user(id,username,password_hash) VALUES(?,?,?)",
                userId, "enc_" + userId.toString().substring(0, 10),
                "synthetic-test-not-a-password-hash");
        admin.update("""
                INSERT INTO public.user_tenant_membership(id,user_id,tenant_id) VALUES(?,?,?)
                """, membershipId, userId, tenantId);
        return membershipId;
    }

    private UUID patient(UUID tenantId) {
        UUID id = UUID.randomUUID();
        admin.update("""
                INSERT INTO public.patient(id,tenant_id,display_name) VALUES(?,?,'FICTIONAL ONLY')
                """, id, tenantId);
        return id;
    }

    private UUID encounter(UUID tenant, UUID branch, UUID patient, UUID doctor) {
        UUID id = UUID.randomUUID();
        admin.update("""
                INSERT INTO encounter_proof.encounter_draft
                    (id,tenant_id,branch_id,patient_id,doctor_membership_id)
                VALUES(?,?,?,?,?)
                """, id, tenant, branch, patient, doctor);
        return id;
    }

    private Fixture seed() {
        UUID a = tenant(), b = tenant();
        UUID ba = branch(a), ba2 = branch(a), bb = branch(b);
        UUID da = doctor(a), da2 = doctor(a), db = doctor(b);
        UUID pa = patient(a), pb = patient(b);
        return new Fixture(a, b, ba, ba2, bb, da, da2, db, pa, pb,
                encounter(a, ba, pa, da),
                encounter(a, ba2, pa, da),
                encounter(a, ba, pa, da2),
                encounter(b, bb, pb, db));
    }

    private Connection runtime() throws SQLException {
        return DriverManager.getConnection(
                postgres.getJdbcUrl(), "aronexa_encounter_proof", TEST_PASSWORD);
    }

    private void bind(Connection c, UUID tenant, UUID branch, UUID doctor) throws SQLException {
        setLocal(c, "aronexa.tenant_id", tenant.toString());
        setLocal(c, "aronexa.branch_id", branch.toString());
        setLocal(c, "aronexa.doctor_membership_id", doctor.toString());
    }

    private static void setLocal(Connection c, String key, String value) throws SQLException {
        try (PreparedStatement p = c.prepareStatement("SELECT set_config(?, ?, true)")) {
            p.setString(1, key);
            p.setString(2, value);
            try (ResultSet ignored = p.executeQuery()) { /* local transaction only */ }
        }
    }

    private static int count(Connection c, String table) throws SQLException {
        try (Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT count(*) FROM " + table)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    @Test
    void roleHasNoOwnerBypassOrIdentityAccess() throws Exception {
        assertThat(admin.queryForObject("""
                SELECT rolsuper OR rolbypassrls OR rolcreaterole OR rolcreatedb
                FROM pg_roles WHERE rolname='aronexa_encounter_proof'
                """, Boolean.class)).isFalse();
        assertThat(admin.queryForObject("""
                SELECT has_table_privilege('aronexa_encounter_proof',
                    'encounter_proof.encounter_draft','DELETE')
                """, Boolean.class)).isFalse();
        assertThat(admin.queryForObject("""
                SELECT has_schema_privilege('aronexa_encounter_proof',
                    'encounter_proof','CREATE')
                """, Boolean.class)).isFalse();
        try (Connection c = runtime()) {
            c.setAutoCommit(false);
            assertThatThrownBy(() -> count(c, "public.patient"))
                    .isInstanceOf(SQLException.class);
            c.rollback();
        }
    }

    @Test
    void noBoundSessionHidesAllEncountersAndDeniesInsert() throws Exception {
        Fixture f = seed();
        try (Connection c = runtime()) {
            c.setAutoCommit(false);
            assertThat(count(c, "encounter_proof.encounter_draft")).isZero();
            assertThat(count(c, "encounter_proof.encounter_event")).isZero();
            try (PreparedStatement p = c.prepareStatement("""
                    INSERT INTO encounter_proof.encounter_draft
                    (id,tenant_id,branch_id,patient_id,doctor_membership_id)
                    VALUES(?,?,?,?,?)
                    """)) {
                p.setObject(1, UUID.randomUUID());
                p.setObject(2, f.tenantA());
                p.setObject(3, f.branchA());
                p.setObject(4, f.patientA());
                p.setObject(5, f.doctorA());
                assertThatThrownBy(p::executeUpdate).isInstanceOf(SQLException.class);
            }
            c.rollback();
        }
    }

    @Test
    void rlsSeparatesTenantBranchAndAssignedDoctor() throws Exception {
        Fixture f = seed();
        try (Connection c = runtime()) {
            c.setAutoCommit(false);
            bind(c, f.tenantA(), f.branchA(), f.doctorA());
            assertThat(count(c, "encounter_proof.encounter_draft")).isEqualTo(1);
            c.rollback();

            c.setAutoCommit(false);
            bind(c, f.tenantA(), f.otherBranchA(), f.doctorA());
            assertThat(count(c, "encounter_proof.encounter_draft")).isEqualTo(1);
            c.rollback();

            c.setAutoCommit(false);
            bind(c, f.tenantA(), f.branchA(), f.otherDoctorA());
            assertThat(count(c, "encounter_proof.encounter_draft")).isEqualTo(1);
            c.rollback();

            c.setAutoCommit(false);
            bind(c, f.tenantB(), f.branchB(), f.doctorB());
            assertThat(count(c, "encounter_proof.encounter_draft")).isEqualTo(1);
            c.rollback();
        }
    }

    @Test
    void mismatchedScopesCannotReadAnEncounterOrReassignImmutableIdentity() throws Exception {
        Fixture f = seed();
        try (Connection c = runtime()) {
            c.setAutoCommit(false);
            bind(c, f.tenantA(), f.branchA(), f.doctorA());
            try (PreparedStatement p = c.prepareStatement("""
                    SELECT count(*) FROM encounter_proof.encounter_draft WHERE id=?
                    """)) {
                p.setObject(1, f.encounterB());
                try (ResultSet rs = p.executeQuery()) {
                    rs.next();
                    assertThat(rs.getInt(1)).isZero();
                }
            }
            try (PreparedStatement p = c.prepareStatement("""
                    UPDATE encounter_proof.encounter_draft SET patient_id=? WHERE id=?
                    """)) {
                p.setObject(1, f.patientB());
                p.setObject(2, f.encounterA());
                assertThatThrownBy(p::executeUpdate).isInstanceOf(SQLException.class);
            }
            c.rollback();
        }
    }

    @Test
    void cannotInsertAnotherTenantsEncounterWithLocalRole() throws Exception {
        Fixture f = seed();
        try (Connection c = runtime()) {
            c.setAutoCommit(false);
            bind(c, f.tenantA(), f.branchA(), f.doctorA());
            try (PreparedStatement p = c.prepareStatement("""
                    INSERT INTO encounter_proof.encounter_draft
                        (id,tenant_id,branch_id,patient_id,doctor_membership_id)
                    VALUES(?,?,?,?,?)
                    """)) {
                p.setObject(1, UUID.randomUUID());
                p.setObject(2, f.tenantB());
                p.setObject(3, f.branchB());
                p.setObject(4, f.patientB());
                p.setObject(5, f.doctorB());
                assertThatThrownBy(p::executeUpdate).isInstanceOf(SQLException.class);
            }
            c.rollback();
        }
    }

    @Test
    void revisionGuardAndMetadataOnlyAppendAuditWorkInOneTransaction() throws Exception {
        Fixture f = seed();
        try (Connection c = runtime()) {
            c.setAutoCommit(false);
            bind(c, f.tenantA(), f.branchA(), f.doctorA());
            try (PreparedStatement p = c.prepareStatement("""
                    UPDATE encounter_proof.encounter_draft
                    SET revision=revision+1, stage='CONSULTATION_DRAFT',note=?
                    WHERE id=? AND revision=0
                    """)) {
                p.setString(1, "Synthetic note — not a patient");
                p.setObject(2, f.encounterA());
                assertThat(p.executeUpdate()).isEqualTo(1);
                assertThat(p.executeUpdate()).isZero(); // same old expected revision
            }
            try (PreparedStatement p = c.prepareStatement("""
                    INSERT INTO encounter_proof.encounter_event
                    (id,tenant_id,branch_id,doctor_membership_id,encounter_id,revision,action)
                    VALUES(?,?,?,?,?,1,'CONSULT_DRAFT')
                    """)) {
                p.setObject(1, UUID.randomUUID());
                p.setObject(2, f.tenantA());
                p.setObject(3, f.branchA());
                p.setObject(4, f.doctorA());
                p.setObject(5, f.encounterA());
                assertThat(p.executeUpdate()).isEqualTo(1);
            }
            c.commit();
            c.setAutoCommit(false);
            bind(c, f.tenantA(), f.branchA(), f.doctorA());
            assertThat(count(c, "encounter_proof.encounter_event")).isEqualTo(1);
            try (Statement s = c.createStatement()) {
                assertThatThrownBy(() -> s.executeUpdate(
                        "DELETE FROM encounter_proof.encounter_event WHERE false"))
                        .isInstanceOf(SQLException.class);
            }
            c.rollback();
        }
        assertThat(admin.queryForObject("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_schema='encounter_proof' AND table_name='encounter_event'
                AND column_name IN ('note','patient_name','clinical_text','payload')
                """, Integer.class)).isZero();
    }

    @Test
    void tenantSettingDisappearsAfterCommitAndRollback() throws Exception {
        Fixture f = seed();
        try (Connection c = runtime()) {
            c.setAutoCommit(false);
            bind(c, f.tenantA(), f.branchA(), f.doctorA());
            assertThat(count(c, "encounter_proof.encounter_draft")).isEqualTo(1);
            c.commit();
            c.setAutoCommit(false);
            assertThat(count(c, "encounter_proof.encounter_draft")).isZero();
            bind(c, f.tenantB(), f.branchB(), f.doctorB());
            assertThat(count(c, "encounter_proof.encounter_draft")).isEqualTo(1);
            c.rollback();
            c.setAutoCommit(false);
            assertThat(count(c, "encounter_proof.encounter_draft")).isZero();
            c.rollback();
        }
    }

    @Test
    void compositeFkPreventsCrossTenantPatientLinkEvenForTestOwner() {
        Fixture f = seed();
        assertThatThrownBy(() -> admin.update("""
                INSERT INTO encounter_proof.encounter_draft
                (id,tenant_id,branch_id,patient_id,doctor_membership_id)
                VALUES(?,?,?,?,?)
                """, UUID.randomUUID(), f.tenantA(), f.branchA(), f.patientB(), f.doctorA()))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
}
