package com.argdigital.aronexa.patient;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** B2: real role permissions and forced RLS in disposable PostgreSQL. No PHI. */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class PatientRuntimeBoundaryTests {
    @Container @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine")
            .withInitScript("db/test/role-bootstrap.sql")
            .withDatabaseName("aronexa_patient_runtime_test")
            .withUsername("aronexa_patient_runtime_test")
            .withPassword("ephemeral-admin-only");

    @Autowired JdbcTemplate admin;

    record Fixture(UUID tenantA, UUID tenantB, UUID memberA, UUID otherMemberA,
                   UUID memberB, UUID branchA, UUID otherBranchA, UUID branchB,
                   UUID registrationA, UUID otherRegistrationA, UUID registrationB) {}

    private UUID tenant() {
        UUID t=UUID.randomUUID();
        admin.update("INSERT INTO tenant(id,code,display_name) VALUES (?,?,?)",
                t,"t_"+t.toString().substring(0,8),"Synthetic");
        return t;
    }

    private UUID member(UUID tenant) {
        UUID user=UUID.randomUUID(), membership=UUID.randomUUID();
        admin.update("INSERT INTO app_user(id,username,password_hash) VALUES (?,?,?)",
                user,"u_"+user.toString().substring(0,8),"synthetic-no-real-password");
        admin.update("INSERT INTO user_tenant_membership(id,user_id,tenant_id) VALUES (?,?,?)",
                membership,user,tenant);
        return membership;
    }

    private UUID branch(UUID tenant) {
        UUID facility=UUID.randomUUID(), id=UUID.randomUUID();
        admin.update("""
                INSERT INTO facility(id,tenant_id,code,display_name,facility_kind)
                VALUES (?,?,?,'Synthetic facility','CLINIC')
                """,facility,tenant,"f_"+facility.toString().substring(0,8));
        admin.update("""
                INSERT INTO branch(id,tenant_id,facility_id,code,display_name)
                VALUES (?,?,?,?,'Synthetic branch')
                """,id,tenant,facility,"b_"+id.toString().substring(0,8));
        return id;
    }

    private UUID patient(UUID tenant) {
        UUID id=UUID.randomUUID();
        admin.update("INSERT INTO patient(id,tenant_id,display_name) VALUES (?,?,?)",
                id,tenant,"Synthetic Only");
        return id;
    }

    private UUID reg(UUID tenant, UUID member, UUID branch, UUID patient) {
        UUID id=UUID.randomUUID();
        admin.update("""
                INSERT INTO patient_registration
                (id,tenant_id,patient_id,branch_id,registration_source,registered_by_membership_id)
                VALUES (?,?,?,?,'RECEPTION',?)
                """,id,tenant,patient,branch,member);
        return id;
    }

    private Fixture seed() {
        UUID a=tenant(), b=tenant();
        UUID ma=member(a), other=member(a), mb=member(b);
        UUID ba=branch(a), otherBranch=branch(a), bb=branch(b);
        admin.update("""
                INSERT INTO org_membership_branch_scope(tenant_id,membership_id,branch_id)
                VALUES (?,?,?)
                """,a,ma,ba);
        admin.update("""
                INSERT INTO org_membership_branch_scope(tenant_id,membership_id,branch_id)
                VALUES (?,?,?)
                """,a,other,otherBranch);
        admin.update("""
                INSERT INTO org_membership_branch_scope(tenant_id,membership_id,branch_id)
                VALUES (?,?,?)
                """,b,mb,bb);
        UUID pa=patient(a), pb=patient(b);
        UUID ra=reg(a,ma,ba,pa);
        UUID otherReg=reg(a,other,otherBranch,pa);
        UUID rb=reg(b,mb,bb,pb);
        return new Fixture(a,b,ma,other,mb,ba,otherBranch,bb,ra,otherReg,rb);
    }

    private Connection patientRole() throws SQLException {
        return DriverManager.getConnection(postgres.getJdbcUrl(),
                "aronexa_patient_runtime","only-for-ephemeral-patient-role");
    }

    private static void bind(Connection c, UUID tenant, UUID member) throws SQLException {
        try (PreparedStatement statement=c.prepareStatement("""
                SELECT set_config('aronexa.tenant_id',?,true),
                       set_config('aronexa.membership_id',?,true)
                """)) {
            statement.setString(1,tenant.toString());
            statement.setString(2,member.toString());
            statement.executeQuery().close();
        }
    }

    private static int count(Connection c, String relation) throws SQLException {
        try(Statement s=c.createStatement(); ResultSet rs=s.executeQuery(
                "SELECT count(*) FROM public."+relation)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    @Test
    void patientRuntimeIsNotOwnerSuperuserOrBypassRlsAndNeverGetsClinicalRead() throws Exception {
        assertThat(admin.queryForObject("""
                SELECT NOT (rolsuper OR rolbypassrls OR rolcreatedb OR rolcreaterole)
                  FROM pg_roles WHERE rolname='aronexa_patient_runtime'
                """, Boolean.class)).isTrue();
        try(Connection c=patientRole();Statement s=c.createStatement()) {
            for(String table : List.of("patient","patient_identifier","patient_contact",
                    "patient_relationship","patient_identity_audit","app_user","auth_session",
                    "patient_registration_access_audit")) {
                assertThatThrownBy(() -> count(c,table)).isInstanceOf(SQLException.class);
            }
            for(String table:List.of("patient_registration","module_registry",
                    "tenant_module_state","org_membership_branch_scope")) {
                try(ResultSet rs=s.executeQuery(
                        "SELECT has_table_privilege(current_user,'public."+table+"','SELECT')")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getBoolean(1)).isTrue();
                }
            }
            for(String table:List.of("patient_registration","module_registry",
                    "tenant_module_state","org_membership_branch_scope")) {
                try(ResultSet rs=s.executeQuery(
                        "SELECT has_table_privilege(current_user,'public."+table+"','INSERT')")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getBoolean(1)).isFalse();
                }
            }
            assertThat(admin.queryForObject("""
                    SELECT relforcerowsecurity FROM pg_class
                    WHERE oid='public.patient_registration'::regclass
                    """,Boolean.class)).isTrue();
        }
    }

    @Test
    void missingTenantOrActorContextShowsNoRegistrationEvenThoughSelectIsGranted()
            throws Exception {
        Fixture f=seed();
        try(Connection c=patientRole()) {
            c.setAutoCommit(false);
            assertThat(count(c,"patient_registration")).isZero();
            assertThat(count(c,"org_membership_branch_scope")).isZero();
            // Tenant alone is NOT enough; staff membership is also required.
            try(PreparedStatement p=c.prepareStatement(
                    "SELECT set_config('aronexa.tenant_id',?,true)")) {
                p.setString(1,f.tenantA().toString());
                p.executeQuery().close();
            }
            assertThat(count(c,"patient_registration")).isZero();
            c.rollback();
        }
    }

    @Test
    void tenantAndMemberScopedRlsDeniesOtherBranchAndOtherTenant() throws Exception {
        Fixture f=seed();
        try(Connection c=patientRole()) {
            c.setAutoCommit(false);
            bind(c,f.tenantA(),f.memberA());
            assertThat(count(c,"patient_registration")).isEqualTo(1);
            assertThat(count(c,"org_membership_branch_scope")).isEqualTo(1);
            try(PreparedStatement ps=c.prepareStatement(
                    "SELECT id FROM patient_registration")) {
                try(ResultSet rs=ps.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getObject(1,UUID.class)).isEqualTo(f.registrationA());
                    assertThat(rs.next()).isFalse();
                }
            }
            c.commit();
            c.setAutoCommit(false);
            assertThat(count(c,"patient_registration")).isZero();
            bind(c,f.tenantA(),f.otherMemberA());
            assertThat(count(c,"patient_registration")).isEqualTo(1);
            try(PreparedStatement ps=c.prepareStatement(
                    "SELECT id FROM patient_registration")) {
                try(ResultSet rs=ps.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getObject(1,UUID.class)).isEqualTo(f.otherRegistrationA());
                    assertThat(rs.next()).isFalse();
                }
            }
            c.rollback();
            c.setAutoCommit(false);
            bind(c,f.tenantB(),f.memberB());
            assertThat(count(c,"patient_registration")).isEqualTo(1);
            c.rollback();
        }
    }

    @Test
    void patientRoleCannotInsertUpdateOrDeleteRegistrationOrChangeModuleActivation()
            throws Exception {
        Fixture f=seed();
        try(Connection c=patientRole()) {
            c.setAutoCommit(false);
            bind(c,f.tenantA(),f.memberA());
            try(Statement s=c.createStatement()) {
                assertThatThrownBy(() -> s.executeUpdate(
                        "UPDATE patient_registration SET registered_at=NOW() WHERE false"))
                        .isInstanceOf(SQLException.class);
            }
            c.rollback();
            c.setAutoCommit(false);
            bind(c,f.tenantA(),f.memberA());
            try(Statement s=c.createStatement()) {
                assertThatThrownBy(() -> s.executeUpdate(
                        "DELETE FROM tenant_module_state WHERE false"))
                        .isInstanceOf(SQLException.class);
            }
            c.rollback();
        }
    }

    @Test
    void auditInsertRequiresMatchingActorAndCorrectRegistrationBranch() throws Exception {
        Fixture f=seed();
        try(Connection c=patientRole()) {
            c.setAutoCommit(false);
            bind(c,f.tenantA(),f.memberA());
            try(PreparedStatement ps=c.prepareStatement("""
                    INSERT INTO patient_registration_access_audit
                    (id,tenant_id,membership_id,branch_id,registration_id,action)
                    VALUES (?,?,?,?,?,'READ')
                    """)) {
                ps.setObject(1,UUID.randomUUID());
                ps.setObject(2,f.tenantA());
                ps.setObject(3,f.memberA());
                ps.setObject(4,f.branchA());
                ps.setObject(5,f.registrationA());
                assertThat(ps.executeUpdate()).isEqualTo(1);
            }
            c.commit();
            assertThat(admin.queryForObject("""
                    SELECT count(*) FROM patient_registration_access_audit
                    WHERE tenant_id=? AND membership_id=? AND registration_id=?
                    """, Integer.class,f.tenantA(),f.memberA(),f.registrationA())).isEqualTo(1);

            c.setAutoCommit(false);
            bind(c,f.tenantA(),f.memberA());
            try(PreparedStatement ps=c.prepareStatement("""
                    INSERT INTO patient_registration_access_audit
                    (id,tenant_id,membership_id,branch_id,registration_id,action)
                    VALUES (?,?,?,?,?,'READ')
                    """)) {
                ps.setObject(1,UUID.randomUUID());
                ps.setObject(2,f.tenantA());
                ps.setObject(3,f.otherMemberA()); // cannot spoof another actor
                ps.setObject(4,f.otherBranchA());
                ps.setObject(5,f.otherRegistrationA());
                assertThatThrownBy(ps::executeUpdate).isInstanceOf(SQLException.class);
            }
            c.rollback();
        }
        assertThatThrownBy(() -> admin.update(
                "DELETE FROM patient_registration_access_audit WHERE tenant_id=?",
                f.tenantA())).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
}
