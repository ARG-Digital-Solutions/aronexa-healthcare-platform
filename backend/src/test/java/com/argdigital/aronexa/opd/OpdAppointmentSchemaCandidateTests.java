package com.argdigital.aronexa.opd;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.assertj.core.api.Assertions.*;

/**
 * R1-10B1: real PostgreSQL 17 structural acceptance for a REVIEW-ONLY schema.
 * Application and session SQL authorization are NOT implemented by this test.
 * All fixture writes use a privileged Testcontainers migrator, never a clinic
 * database, role, or production HTTP route.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OpdAppointmentSchemaCandidateTests {
    @Container @ServiceConnection
    static PostgreSQLContainer postgres=new PostgreSQLContainer("postgres:17-alpine")
            .withInitScript("db/test/role-bootstrap.sql")
            .withDatabaseName("aronexa_r110b1_disposable")
            .withUsername("aronexa_r110b1_disposable")
            .withPassword("disposable-r110b1-only");

    @Autowired JdbcTemplate admin;
    private static final LocalDate DAY=LocalDate.of(2026,11,13);

    @BeforeAll
    void stageReviewOnlySchemaInFreshContainer() throws Exception {
        Path candidate=Path.of("deploy","opd","review-only",
                "V11__opd_appointment_schema_candidate.sql").toAbsolutePath();
        if (!Files.isRegularFile(candidate)) {
            candidate=Path.of("..","deploy","opd","review-only",
                    "V11__opd_appointment_schema_candidate.sql").toAbsolutePath();
        }
        if (!Files.isRegularFile(candidate)) {
            throw new IllegalStateException("Missing review-only OPD schema candidate");
        }
        try(Connection c=admin.getDataSource().getConnection()){
            // Source is OUTSIDE production Flyway; only a fresh Testcontainer
            // executes this file. pgJDBC handles dollar-quoted function SQL.
            ScriptUtils.executeSqlScript(c,new EncodedResource(
                new FileSystemResource(candidate)),
                false,false,ScriptUtils.DEFAULT_COMMENT_PREFIX,
                ScriptUtils.EOF_STATEMENT_SEPARATOR,
                ScriptUtils.DEFAULT_BLOCK_COMMENT_START_DELIMITER,
                ScriptUtils.DEFAULT_BLOCK_COMMENT_END_DELIMITER);
        }
    }

    record SyntheticClinic(UUID tenant,UUID branch,UUID patient,
                           UUID registration,UUID booker,UUID doctor) {}

    private SyntheticClinic fixture(){
        UUID tenant=UUID.randomUUID(),branch=UUID.randomUUID();
        UUID facility=UUID.randomUUID(),patient=UUID.randomUUID();
        admin.update("INSERT INTO public.tenant(id,code,display_name) VALUES(?,?,?)",
            tenant,"r110_"+tenant.toString().substring(0,9),"FICTIONAL R110B1");
        UUID booker=membership(tenant),doctor=membership(tenant);
        admin.update("""
            INSERT INTO public.facility(id,tenant_id,code,display_name,facility_kind)
            VALUES (?,?,?,'FICTIONAL facility','CLINIC')
            """,facility,tenant,"f_"+facility.toString().substring(0,8));
        admin.update("""
            INSERT INTO public.branch(id,tenant_id,facility_id,code,display_name)
            VALUES (?,?,?,?,'FICTIONAL branch')
            """,branch,tenant,facility,"b_"+branch.toString().substring(0,8));
        admin.update("""
            INSERT INTO public.org_membership_branch_scope
                   (tenant_id,membership_id,branch_id)
            VALUES (?,?,?)
            """,tenant,booker,branch);
        admin.update("""
            INSERT INTO public.org_membership_branch_scope
                   (tenant_id,membership_id,branch_id)
            VALUES (?,?,?)
            """,tenant,doctor,branch);
        admin.update("""
            INSERT INTO public.patient(id,tenant_id,display_name)
            VALUES (?,?,'FICTIONAL - NOT A PATIENT')
            """,patient,tenant);
        UUID registration=UUID.randomUUID();
        admin.update("""
            INSERT INTO public.patient_registration(id,tenant_id,patient_id,
                     branch_id,registration_source,registered_by_membership_id)
            VALUES (?,?,?,?,'RECEPTION',?)
            """,registration,tenant,patient,branch,booker);
        return new SyntheticClinic(tenant,branch,patient,registration,booker,doctor);
    }

    private UUID membership(UUID tenant){
        UUID user=UUID.randomUUID(),member=UUID.randomUUID();
        admin.update("INSERT INTO public.app_user(id,username,password_hash) VALUES (?,?,?)",
            user,"r110_"+user.toString().substring(0,12),"disposable-not-login");
        admin.update("""
            INSERT INTO public.user_tenant_membership(id,user_id,tenant_id)
            VALUES (?,?,?)
            """,member,user,tenant);
        return member;
    }

    private UUID book(SyntheticClinic clinic,LocalTime slot,UUID request) {
        UUID id=UUID.randomUUID();
        admin.update("""
            INSERT INTO opd_candidate.appointment
              (id,tenant_id,branch_id,patient_id,registration_id,
               doctor_membership_id,booking_request_id,
               booked_by_membership_id,updated_by_membership_id,
               visit_date,slot_start,status,revision)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,'BOOKED',1)
            """,id,clinic.tenant(),clinic.branch(),clinic.patient(),
                clinic.registration(),clinic.doctor(),request,clinic.booker(),
                clinic.booker(),DAY,slot);
        return id;
    }

    private int events(UUID id){
        return admin.queryForObject("""
             SELECT count(*) FROM opd_candidate.appointment_event
             WHERE appointment_id=?
             """,Integer.class,id);
    }

    @Test
    void exactRegistrationAndFirstBookingPersistAcrossIndependentConnection() throws Exception {
        SyntheticClinic clinic=fixture();
        UUID visit=book(clinic,LocalTime.of(9,0),UUID.randomUUID());
        assertThat(events(visit)).isEqualTo(1);
        try(Connection c=admin.getDataSource().getConnection();
            PreparedStatement ps=c.prepareStatement("""
                SELECT patient_id,registration_id,status,revision
                FROM opd_candidate.appointment
                WHERE id=? AND tenant_id=? AND branch_id=?
                """)){
            ps.setObject(1,visit);ps.setObject(2,clinic.tenant());ps.setObject(3,clinic.branch());
            try(var rs=ps.executeQuery()){
                assertThat(rs.next()).isTrue();
                assertThat(rs.getObject("patient_id",UUID.class)).isEqualTo(clinic.patient());
                assertThat(rs.getObject("registration_id",UUID.class)).isEqualTo(clinic.registration());
                assertThat(rs.getString("status")).isEqualTo("BOOKED");
                assertThat(rs.getInt("revision")).isEqualTo(1);
                assertThat(rs.next()).isFalse();
            }
        }
    }

    @Test
    void exactCompositeTenantBranchPatientRegistrationForeignKeyRejectsSubstitution() {
        SyntheticClinic a=fixture(),b=fixture();
        assertThatThrownBy(()->admin.update("""
            INSERT INTO opd_candidate.appointment
              (id,tenant_id,branch_id,patient_id,registration_id,
               doctor_membership_id,booking_request_id,booked_by_membership_id,
               updated_by_membership_id,visit_date,slot_start,status,revision)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,'BOOKED',1)
            """,UUID.randomUUID(),a.tenant(),a.branch(),a.patient(),b.registration(),
            a.doctor(),UUID.randomUUID(),a.booker(),a.booker(),DAY,
            LocalTime.of(9,15))).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->admin.update("""
            INSERT INTO opd_candidate.appointment
              (id,tenant_id,branch_id,patient_id,registration_id,
               doctor_membership_id,booking_request_id,booked_by_membership_id,
               updated_by_membership_id,visit_date,slot_start,status,revision)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,'BOOKED',1)
            """,UUID.randomUUID(),a.tenant(),a.branch(),b.patient(),a.registration(),
            a.doctor(),UUID.randomUUID(),a.booker(),a.booker(),DAY,
            LocalTime.of(9,30))).isInstanceOf(DataAccessException.class);
    }

    @Test
    void doctorSlotAndRequestIdRemainUniqueEvenWithoutAnApplication() {
        SyntheticClinic a=fixture();
        UUID bookingRequest=UUID.randomUUID();
        book(a,LocalTime.of(10,0),bookingRequest);
        assertThatThrownBy(()->book(a,LocalTime.of(10,0),UUID.randomUUID()))
            .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->book(a,LocalTime.of(10,15),bookingRequest))
            .isInstanceOf(DataAccessException.class);
    }

    @Test
    void checkInAuditsRevisionAndRejectsPostCheckinMutation() {
        SyntheticClinic a=fixture();
        UUID id=book(a,LocalTime.of(11,0),UUID.randomUUID());
        assertThat(admin.update("""
            UPDATE opd_candidate.appointment
               SET status='CHECKED_IN',revision=2
             WHERE id=? AND revision=1
            """,id)).isEqualTo(1);
        assertThat(events(id)).isEqualTo(2);
        assertThatThrownBy(()->admin.update("""
            UPDATE opd_candidate.appointment
               SET status='CANCELLED',revision=3 WHERE id=?
            """,id)).isInstanceOf(DataAccessException.class);
        assertThat(events(id)).isEqualTo(2);
    }

    @Test
    void cancellationReleasesSlotAndCannotEraseAudit() {
        SyntheticClinic a=fixture();
        UUID id=book(a,LocalTime.of(13,0),UUID.randomUUID());
        admin.update("""
            UPDATE opd_candidate.appointment
               SET status='CANCELLED',revision=2 WHERE id=?
            """,id);
        assertThat(events(id)).isEqualTo(2);
        UUID replacement=book(a,LocalTime.of(13,0),UUID.randomUUID());
        assertThat(events(replacement)).isEqualTo(1);
        assertThatThrownBy(()->admin.update("""
            DELETE FROM opd_candidate.appointment_event WHERE appointment_id=?
            """,id)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->admin.update("""
            UPDATE opd_candidate.appointment_event SET action='BOOKED'
            WHERE appointment_id=? AND revision=2
            """,id)).isInstanceOf(DataAccessException.class);
    }

    @Test
    void unappliedSchemaHasForcedRlsAndNoPatientRoleOrPublicSelectGrant() {
        assertThat(admin.queryForObject("""
            SELECT relrowsecurity AND relforcerowsecurity
              FROM pg_catalog.pg_class
             WHERE oid='opd_candidate.appointment'::regclass
            """,Boolean.class)).isTrue();
        assertThat(admin.queryForObject("""
            SELECT relrowsecurity AND relforcerowsecurity
              FROM pg_catalog.pg_class
             WHERE oid='opd_candidate.appointment_event'::regclass
            """,Boolean.class)).isTrue();
        assertThat(admin.queryForObject("""
            SELECT has_table_privilege('aronexa_patient_runtime',
              'opd_candidate.appointment','SELECT')
            """,Boolean.class)).isFalse();
        assertThat(admin.queryForObject("""
            SELECT has_schema_privilege('aronexa_patient_runtime',
              'opd_candidate','USAGE')
            """,Boolean.class)).isFalse();
        assertThat(admin.queryForObject("""
            SELECT NOT EXISTS (
                SELECT 1 FROM pg_catalog.pg_class c,
                LATERAL pg_catalog.aclexplode(c.relacl) a
                WHERE c.oid='opd_candidate.appointment'::regclass
                  AND a.grantee=0
                  AND a.privilege_type IN ('SELECT','INSERT','UPDATE','DELETE')
            )
            """,Boolean.class)).isTrue();
    }
}
