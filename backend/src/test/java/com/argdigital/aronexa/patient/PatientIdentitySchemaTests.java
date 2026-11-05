package com.argdigital.aronexa.patient;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** P01-T07-A: synthetic data and real PostgreSQL, not registration/API acceptance. */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class PatientIdentitySchemaTests {
    @Container @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine")
            .withInitScript("db/test/role-bootstrap.sql")
            .withDatabaseName("aronexa_patient_test")
            .withUsername("aronexa_patient_test")
            .withPassword("ephemeral-test-only");

    @Autowired JdbcTemplate admin;

    private UUID tenant() {
        UUID id = UUID.randomUUID();
        admin.update("INSERT INTO tenant(id,code,display_name) VALUES (?,?,?)",
                id, "t_" + id.toString().substring(0,8), "Synthetic hospital");
        return id;
    }

    private UUID patient(UUID tenant, String name) {
        UUID id = UUID.randomUUID();
        admin.update("INSERT INTO patient(id,tenant_id,display_name) VALUES (?,?,?)",
                id, tenant, name);
        return id;
    }

    private UUID branch(UUID tenant) {
        UUID facility=UUID.randomUUID(), branch=UUID.randomUUID();
        admin.update("""
                INSERT INTO facility(id,tenant_id,code,display_name,facility_kind)
                VALUES (?,?, 'clinic', 'Synthetic facility', 'CLINIC')
                """,facility,tenant);
        admin.update("""
                INSERT INTO branch(id,tenant_id,facility_id,code,display_name)
                VALUES (?,?,?, 'main', 'Synthetic branch')
                """,branch,tenant,facility);
        return branch;
    }

    private UUID registration(UUID tenant,UUID patient,UUID branch,String hash) {
        UUID id=UUID.randomUUID();
        admin.update("""
                INSERT INTO patient_registration
                    (id,tenant_id,patient_id,branch_id,registration_source,idempotency_sha256)
                VALUES (?,?,?,?,'RECEPTION',?)
                """,id,tenant,patient,branch,hash);
        return id;
    }

    @Test
    void identityIsSharedAcrossBranchesWithOneMrnPerTenant() {
        UUID t=tenant(), p=patient(t,"Synthetic Test Person");
        UUID a=branch(t);
        registration(t,p,a,"a".repeat(64));
        registration(t,p,a,"b".repeat(64));
        UUID identifier=UUID.randomUUID();
        admin.update("""
                INSERT INTO patient_identifier
                (id,tenant_id,patient_id,identifier_value)
                VALUES (?,?,?,'PAT-000124')
                """,identifier,t,p);
        assertThat(admin.queryForObject(
                "SELECT count(*) FROM patient WHERE tenant_id=? AND id=?",
                Integer.class,t,p)).isEqualTo(1);
        assertThat(admin.queryForObject(
                "SELECT count(*) FROM patient_registration WHERE tenant_id=? AND patient_id=?",
                Integer.class,t,p)).isEqualTo(2);
        assertThatThrownBy(() -> admin.update("""
                INSERT INTO patient_identifier
                (id,tenant_id,patient_id,identifier_value)
                VALUES (?,?,?,'PAT-000124')
                """,UUID.randomUUID(),t,patient(t,"Different person")))
                .isInstanceOf(DataAccessException.class);
        UUID otherTenant=tenant(), other=patient(otherTenant,"Other patient");
        admin.update("""
                INSERT INTO patient_identifier
                (id,tenant_id,patient_id,identifier_value)
                VALUES (?,?,?,'PAT-000124')
                """,UUID.randomUUID(),otherTenant,other);
    }

    @Test
    void compositeForeignKeysRejectCrossTenantLinksAndWrongBranches() {
        UUID a=tenant(), b=tenant();
        UUID p=patient(a,"Patient A"), other=patient(b,"Patient B");
        UUID branchB=branch(b);
        assertThatThrownBy(() -> registration(a,p,branchB,null))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> admin.update("""
                INSERT INTO patient_contact(id,tenant_id,patient_id,contact_type,contact_value)
                VALUES (?,?,?,'MOBILE','synthetic-only')
                """,UUID.randomUUID(),a,other))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> admin.update("""
                INSERT INTO patient_relationship
                    (id,tenant_id,patient_id,related_patient_id,relationship_type)
                VALUES (?,?,?,?,'GUARDIAN')
                """,UUID.randomUUID(),a,p,other))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void familyContactMayBeSharedButDuplicateSubmissionKeyCannot() {
        UUID t=tenant(), p=patient(t,"Synthetic One"), p2=patient(t,"Synthetic Two");
        for (UUID id : new UUID[]{p,p2}) {
            admin.update("""
                    INSERT INTO patient_contact
                        (id,tenant_id,patient_id,contact_type,contact_value,is_primary)
                    VALUES (?,?,?,'MOBILE','9000000000',true)
                    """,UUID.randomUUID(),t,id);
        }
        assertThat(admin.queryForObject(
                "SELECT count(*) FROM patient_contact WHERE tenant_id=? AND contact_value='9000000000'",
                Integer.class,t)).isEqualTo(2);
        UUID br=branch(t);
        registration(t,p,br,"c".repeat(64));
        assertThatThrownBy(() -> registration(t,p2,br,"c".repeat(64)))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void patientIdentityAndMrnAreImmutableAndAuditIsAppendOnly() {
        UUID t=tenant(), p=patient(t,"Synthetic Person"), otherTenant=tenant();
        admin.update("""
                INSERT INTO patient_identifier(id,tenant_id,patient_id,identifier_value)
                VALUES (?,?,?,'PAT-000125')
                """,UUID.randomUUID(),t,p);
        assertThatThrownBy(() -> admin.update(
                "UPDATE patient SET tenant_id=? WHERE id=?",otherTenant,p))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> admin.update(
                "UPDATE patient_identifier SET identifier_value='PAT-000126' WHERE patient_id=?",p))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> admin.update(
                "DELETE FROM patient WHERE id=?",p))
                .isInstanceOf(DataAccessException.class);
        assertThat(admin.queryForObject(
                "SELECT count(*) FROM patient_identity_audit WHERE tenant_id=? AND patient_id=?",
                Integer.class,t,p)).isGreaterThanOrEqualTo(2);
        assertThatThrownBy(() -> admin.update(
                "DELETE FROM patient_identity_audit WHERE tenant_id=? AND patient_id=?",t,p))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void patientTablesHaveRlsButBothRuntimeRolesHaveNoPatientTableAccess() throws Exception {
        UUID t=tenant();
        patient(t,"Synthetic Patient");
        assertThat(admin.queryForObject("""
                SELECT count(*) FROM pg_class
                WHERE relname IN
                  ('patient','patient_identifier','patient_contact',
                   'patient_relationship','patient_registration')
                  AND relrowsecurity AND relforcerowsecurity
                """,Integer.class)).isEqualTo(5);
        for (String username : new String[]{"aronexa_app_runtime","aronexa_auth_runtime"}) {
            String password=username.equals("aronexa_app_runtime")
                    ? "only-for-ephemeral-test-container" : "only-for-ephemeral-auth-role";
            try (Connection c=DriverManager.getConnection(postgres.getJdbcUrl(),username,password)) {
                c.setAutoCommit(false);
                try (PreparedStatement s=c.prepareStatement(
                        "SELECT set_config('aronexa.tenant_id', ?, true)")) {
                    s.setString(1,t.toString());
                    s.executeQuery().close();
                }
                for (String table : new String[]{"patient","patient_identifier",
                        "patient_contact","patient_relationship","patient_registration",
                        "patient_identity_audit"}) {
                    try (Statement statement=c.createStatement()) {
                        assertThatThrownBy(() -> statement.executeQuery(
                                "SELECT count(*) FROM public."+table))
                                .isInstanceOf(SQLException.class);
                    }
                    c.rollback();
                    c.setAutoCommit(false);
                }
                c.rollback();
            }
        }
    }
}
