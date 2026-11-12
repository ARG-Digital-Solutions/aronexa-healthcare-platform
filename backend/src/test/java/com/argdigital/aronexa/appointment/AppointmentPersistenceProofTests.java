package com.argdigital.aronexa.appointment;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * COMM-V1-T14: disposable PG17 appointment persistence proof ONLY.
 *
 * Never installed via application Flyway or registered as a Spring/controller
 * service. Tests use a privileged disposable migrator to seed synthetic data.
 * A real appointment API MUST additionally bind actual bearer/session
 * authorization to a restricted, auditable, least-privileged SQL command.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AppointmentPersistenceProofTests {
    @Container @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine")
            .withInitScript("db/test/role-bootstrap.sql")
            .withDatabaseName("aronexa_appointment_disposable")
            .withUsername("aronexa_appointment_disposable")
            .withPassword("disposable-test-only");

    @Autowired JdbcTemplate admin;
    private static final LocalDate VISIT_DAY = LocalDate.of(2026, 11, 12);
    private static final LocalTime SLOT = LocalTime.of(9, 0);

    @BeforeAll
    void installOnlyIntoNewContainer() throws Exception {
        try (Connection c = admin.getDataSource().getConnection()) {
            // PostgreSQL PL/pgSQL dollar-quoted function bodies contain semicolons.
            // Spring's default ';' splitter would truncate CREATE FUNCTION statements.
            // Send the full synthetic-only script to pgJDBC, whose SQL parser handles
            // dollar quoting correctly; never apply this to a real customer database.
            ScriptUtils.executeSqlScript(c,
                new EncodedResource(new ClassPathResource(
                    "db/test/appointment-persistence-proof.sql")),
                false, false,
                ScriptUtils.DEFAULT_COMMENT_PREFIX,
                ScriptUtils.EOF_STATEMENT_SEPARATOR,
                ScriptUtils.DEFAULT_BLOCK_COMMENT_START_DELIMITER,
                ScriptUtils.DEFAULT_BLOCK_COMMENT_END_DELIMITER);
        }
        // Database created by Testcontainers, never from any owner/dev/customer DB.
        admin.update("UPDATE module_registry SET implemented=true,released=true WHERE code='OPD'");
    }

    record Clinic(UUID tenant, UUID branch, UUID patient, UUID receptionist, UUID doctor,
                  UUID doctorRole) {}

    private UUID tenant() {
        UUID id=UUID.randomUUID();
        admin.update("INSERT INTO tenant(id,code,display_name) VALUES(?,?,?)",
                id,"apt_"+id.toString().substring(0,10),"FICTIONAL appointment tenant");
        admin.update("""
                INSERT INTO tenant_module_state
                    (tenant_id,module_code,entitled,activated,clinically_authorized)
                VALUES (?,'OPD',true,true,true)
                """,id);
        return id;
    }

    private UUID branch(UUID tenant) {
        UUID f=UUID.randomUUID(),b=UUID.randomUUID();
        admin.update("""
                INSERT INTO facility(id,tenant_id,code,display_name,facility_kind)
                VALUES (?,?,?,'FICTIONAL clinic','CLINIC')
                """,f,tenant,"fac_"+f.toString().substring(0,8));
        admin.update("""
                INSERT INTO branch(id,tenant_id,facility_id,code,display_name)
                VALUES (?,?,?,?,'FICTIONAL branch')
                """,b,tenant,f,"br_"+b.toString().substring(0,8));
        return b;
    }

    private UUID membership(UUID tenant, UUID branch, UUID role) {
        UUID user=UUID.randomUUID(),member=UUID.randomUUID();
        admin.update("INSERT INTO app_user(id,username,password_hash) VALUES (?,?,?)",
                user,"apt_"+user.toString().substring(0,12),"not-an-actual-password");
        admin.update("""
                INSERT INTO user_tenant_membership(id,user_id,tenant_id) VALUES(?,?,?)
                """,member,user,tenant);
        admin.update("""
                INSERT INTO org_membership_branch_scope(tenant_id,membership_id,branch_id)
                VALUES(?,?,?)
                """,tenant,member,branch);
        admin.update("""
                INSERT INTO auth_membership_role(tenant_id,membership_id,role_id)
                VALUES(?,?,?)
                """,tenant,member,role);
        return member;
    }

    private Clinic seed() {
        UUID t=tenant(),b=branch(t),p=UUID.randomUUID();
        UUID rRole=UUID.randomUUID(),dRole=UUID.randomUUID();
        admin.update("""
                INSERT INTO auth_role(id,tenant_id,code,display_name)
                VALUES (?,?,'RECEPTION','Synthetic receptionist')
                """,rRole,t);
        admin.update("""
                INSERT INTO auth_role(id,tenant_id,code,display_name)
                VALUES (?,?,'DOCTOR','Synthetic doctor')
                """,dRole,t);
        admin.update("""
                INSERT INTO auth_role_permission(tenant_id,role_id,permission_code)
                VALUES (?,?,'APPOINTMENT_BOOK')
                """,t,rRole);
        UUID receptionist=membership(t,b,rRole),doctor=membership(t,b,dRole);
        admin.update("""
                INSERT INTO patient(id,tenant_id,display_name)
                VALUES (?,?,'SYNTHETIC ONLY - NOT A PATIENT')
                """,p,t);
        admin.update("""
                INSERT INTO patient_registration(id,tenant_id,patient_id,branch_id,
                         registration_source,registered_by_membership_id)
                VALUES (?,?,?,?,'RECEPTION',?)
                """,UUID.randomUUID(),t,p,b,receptionist);
        return new Clinic(t,b,p,receptionist,doctor,dRole);
    }

    private UUID book(Clinic f) {return book(f,f.doctor(),VISIT_DAY,SLOT);}
    private UUID book(Clinic f, UUID doctor, LocalDate day, LocalTime slot) {
        UUID id=UUID.randomUUID();
        writeBooking(id,f.tenant(),f.branch(),f.patient(),doctor,f.receptionist(),day,slot);
        return id;
    }
    private void writeBooking(UUID id,UUID tenant,UUID branch,UUID patient,UUID doctor,
                              UUID actor,LocalDate date,LocalTime slot) {
        admin.update("""
                INSERT INTO appointment_proof.appointment
                  (id,tenant_id,branch_id,patient_id,doctor_membership_id,
                   booked_by_membership_id,updated_by_membership_id,visit_date,slot_start)
                VALUES (?,?,?,?,?,?,?,?,?)
                """,id,tenant,branch,patient,doctor,actor,actor,date,slot);
    }
    private int transition(UUID id, int expectedRevision, String status, UUID actor) {
        return admin.update("""
                UPDATE appointment_proof.appointment
                   SET status=?,revision=revision+1,updated_by_membership_id=?
                 WHERE id=? AND revision=? AND status='BOOKED'
                """,status,actor,id,expectedRevision);
    }
    private int total(UUID id) {
        return admin.queryForObject("SELECT count(*) FROM appointment_proof.appointment WHERE id=?",
                Integer.class,id);
    }
    private int events(UUID id) {
        return admin.queryForObject("SELECT count(*) FROM appointment_proof.event WHERE appointment_id=?",
                Integer.class,id);
    }
    private UUID addDoctor(Clinic f) {
        return membership(f.tenant(),f.branch(),f.doctorRole());
    }

    @Test
    void bookedAppointmentPersistsAcrossConnectionsAndAutomaticallyAudits() throws Exception {
        Clinic f=seed();
        UUID id=book(f);
        assertThat(total(id)).isOne();
        assertThat(events(id)).isOne();
        try (Connection other=DriverManager.getConnection(postgres.getJdbcUrl(),
                postgres.getUsername(),postgres.getPassword());
             PreparedStatement p=other.prepareStatement("""
                    SELECT status,revision,patient_id,doctor_membership_id
                    FROM appointment_proof.appointment WHERE id=?
                    """)) {
            p.setObject(1,id);
            try(ResultSet rs=p.executeQuery()){
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("status")).isEqualTo("BOOKED");
                assertThat(rs.getInt("revision")).isEqualTo(1);
                assertThat(rs.getObject("patient_id",UUID.class)).isEqualTo(f.patient());
                assertThat(rs.getObject("doctor_membership_id",UUID.class)).isEqualTo(f.doctor());
            }
        }
        assertThat(admin.queryForObject("""
                SELECT event_code FROM appointment_proof.event
                 WHERE appointment_id=? AND revision=1
                """,String.class,id)).isEqualTo("BOOKED");
    }

    @Test
    void sameDoctorCannotBeDoubleBookedEvenWithDifferentPatients() {
        Clinic f=seed();
        UUID booked=book(f);
        UUID second=UUID.randomUUID();
        assertThatThrownBy(() -> writeBooking(second,f.tenant(),f.branch(),
                f.patient(),f.doctor(),f.receptionist(),VISIT_DAY,SLOT))
                .isInstanceOf(DataAccessException.class);
        assertThat(total(booked)).isOne();
        assertThat(total(second)).isZero();
        assertThat(events(booked)).isOne();
        UUID anotherDoctor=addDoctor(f);
        UUID parallel=book(f,anotherDoctor,VISIT_DAY,SLOT);
        assertThat(total(parallel)).isOne();
    }

    @Test
    void cancelledVisitReleasesSlotButKeepsAppendOnlyHistory() {
        Clinic f=seed();
        UUID first=book(f);
        assertThat(transition(first,1,"CANCELLED",f.receptionist())).isOne();
        UUID replacement=book(f);
        assertThat(total(replacement)).isOne();
        assertThat(events(first)).isEqualTo(2);
        assertThat(admin.queryForList("""
                SELECT event_code FROM appointment_proof.event
                 WHERE appointment_id=? ORDER BY revision
                """,String.class,first)).containsExactly("BOOKED","CANCELLED");
    }

    @Test
    void reschedulePersistsNewSlotReleasesOldSlotAndRecordsExactRevisionAudit() {
        Clinic f=seed();
        UUID id=book(f);
        LocalTime replacement=LocalTime.of(9,15);
        assertThat(admin.update("""
                UPDATE appointment_proof.appointment
                   SET slot_start=?,revision=revision+1,updated_by_membership_id=?
                 WHERE id=? AND revision=1 AND status='BOOKED'
                """,replacement,f.receptionist(),id)).isOne();

        assertThat(admin.queryForObject("""
                SELECT slot_start FROM appointment_proof.appointment WHERE id=?
                """,LocalTime.class,id)).isEqualTo(replacement);
        assertThat(admin.queryForList("""
                SELECT event_code FROM appointment_proof.event
                 WHERE appointment_id=? ORDER BY revision
                """,String.class,id)).containsExactly("BOOKED","RESCHEDULED");

        // The moved-to slot remains occupied. The original is now free.
        assertThatThrownBy(() -> book(f,f.doctor(),VISIT_DAY,replacement))
                .isInstanceOf(DataAccessException.class);
        UUID originalSlotNowFree=book(f);
        assertThat(total(originalSlotNowFree)).isOne();

        // A stale revision cannot overwrite a successfully rescheduled visit.
        assertThat(admin.update("""
                UPDATE appointment_proof.appointment
                   SET slot_start=?,revision=revision+1,updated_by_membership_id=?
                 WHERE id=? AND revision=1 AND status='BOOKED'
                """,LocalTime.of(9,30),f.receptionist(),id)).isZero();
        assertThat(events(id)).isEqualTo(2);
    }

    @Test
    void rescheduleMustChangeSlotOrDoctorAndKeepSamePatient() {
        Clinic f=seed();
        UUID id=book(f);
        assertThatThrownBy(() -> admin.update("""
                UPDATE appointment_proof.appointment
                   SET revision=revision+1,updated_by_membership_id=?
                 WHERE id=? AND revision=1
                """,f.receptionist(),id)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> admin.update("""
                UPDATE appointment_proof.appointment
                   SET patient_id=?,slot_start=?,revision=revision+1,
                       updated_by_membership_id=?
                 WHERE id=? AND revision=1
                """,UUID.randomUUID(),LocalTime.of(9,15),f.receptionist(),id))
                .isInstanceOf(DataAccessException.class);
        assertThat(events(id)).isOne();
        assertThat(admin.queryForObject("""
                SELECT revision FROM appointment_proof.appointment WHERE id=?
                """,Integer.class,id)).isEqualTo(1);
    }

    @Test
    void checkInIsTerminalAndDoctorSlotRemainsOccupied() {
        Clinic f=seed();
        UUID id=book(f);
        assertThat(transition(id,1,"CHECKED_IN",f.receptionist())).isOne();
        assertThatThrownBy(() -> book(f)).isInstanceOf(DataAccessException.class);
        assertThat(transition(id,2,"CANCELLED",f.receptionist())).isZero();
        assertThat(events(id)).isEqualTo(2);
    }

    @Test
    void staleRevisionRejectsMutationWithoutNewAuditEvent() {
        Clinic f=seed();
        UUID id=book(f);
        assertThat(transition(id,1,"CHECKED_IN",f.receptionist())).isOne();
        assertThat(transition(id,1,"CANCELLED",f.receptionist())).isZero();
        assertThat(events(id)).isEqualTo(2);
    }

    @Test
    void crossTenantPatientOrBranchCannotBeBoundToVisit() {
        Clinic a=seed(), b=seed();
        assertThatThrownBy(() -> writeBooking(UUID.randomUUID(),a.tenant(),a.branch(),
                b.patient(),a.doctor(),a.receptionist(),VISIT_DAY,SLOT))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> writeBooking(UUID.randomUUID(),a.tenant(),b.branch(),
                a.patient(),a.doctor(),a.receptionist(),VISIT_DAY,SLOT))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void patientMustBeRegisteredInBookingBranch() {
        Clinic f=seed();
        UUID secondBranch=branch(f.tenant());
        admin.update("""
                INSERT INTO org_membership_branch_scope(tenant_id,membership_id,branch_id)
                VALUES(?,?,?)
                """,f.tenant(),f.receptionist(),secondBranch);
        admin.update("""
                INSERT INTO org_membership_branch_scope(tenant_id,membership_id,branch_id)
                VALUES(?,?,?)
                """,f.tenant(),f.doctor(),secondBranch);
        assertThatThrownBy(() -> writeBooking(UUID.randomUUID(),f.tenant(),secondBranch,
                f.patient(),f.doctor(),f.receptionist(),VISIT_DAY,SLOT))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void unassignedOrNonDoctorMembershipCannotBeUsedAsDoctor() {
        Clinic f=seed();
        UUID anotherBranch=branch(f.tenant());
        UUID unassigned=membership(f.tenant(),anotherBranch,f.doctorRole());
        assertThatThrownBy(() -> book(f,unassigned,VISIT_DAY,SLOT))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> book(f,f.receptionist(),VISIT_DAY,SLOT))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void revokedStaffOrDoctorBlocksNewOrExistingWrites() {
        Clinic f=seed();
        UUID id=book(f);
        admin.update("UPDATE user_tenant_membership SET enabled=false WHERE id=?",f.receptionist());
        assertThatThrownBy(() -> book(f)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> transition(id,1,"CANCELLED",f.receptionist()))
                .isInstanceOf(DataAccessException.class);
        admin.update("UPDATE user_tenant_membership SET enabled=true WHERE id=?",f.receptionist());
        admin.update("UPDATE user_tenant_membership SET enabled=false WHERE id=?",f.doctor());
        assertThatThrownBy(() -> book(f)).isInstanceOf(DataAccessException.class);
        assertThat(events(id)).isOne();
    }

    @Test
    void disabledModuleStopsBookingAndUpdatesWithoutPartialAudit() {
        Clinic f=seed();
        UUID id=book(f);
        admin.update("""
                UPDATE tenant_module_state SET activated=false
                WHERE tenant_id=? AND module_code='OPD'
                """,f.tenant());
        assertThatThrownBy(() -> book(f)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> transition(id,1,"CHECKED_IN",f.receptionist()))
                .isInstanceOf(DataAccessException.class);
        assertThat(events(id)).isOne();
    }

    @Test
    void canceledOrCheckedInAppointmentCannotBeRevisedOrReassigned() {
        Clinic f=seed();
        UUID id=book(f);
        assertThat(transition(id,1,"CANCELLED",f.receptionist())).isOne();
        assertThatThrownBy(() -> admin.update("""
                UPDATE appointment_proof.appointment SET revision=3,status='BOOKED'
                WHERE id=?
                """,id)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> admin.update("""
                UPDATE appointment_proof.appointment SET patient_id=?,revision=3
                WHERE id=?
                """,UUID.randomUUID(),id)).isInstanceOf(DataAccessException.class);
        assertThat(events(id)).isEqualTo(2);
    }

    @Test
    void auditContainsNoClinicalNamesAndCannotBeChanged() {
        Clinic f=seed();
        UUID id=book(f);
        assertThat(admin.queryForObject("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_schema='appointment_proof' AND table_name='event'
                  AND column_name IN ('patient_name','patient_dob','note','phone','payload')
                """,Integer.class)).isZero();
        assertThatThrownBy(() -> admin.update("""
                DELETE FROM appointment_proof.event WHERE appointment_id=?
                """,id)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> admin.update("""
                UPDATE appointment_proof.event SET event_code='CANCELLED'
                WHERE appointment_id=?
                """,id)).isInstanceOf(DataAccessException.class);
        assertThat(events(id)).isOne();
    }

    @Test
    void invalidTimesAndDatesAreDatabaseRejected() {
        Clinic f=seed();
        assertThatThrownBy(() -> book(f,f.doctor(),VISIT_DAY,LocalTime.of(8,45)))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> book(f,f.doctor(),VISIT_DAY,LocalTime.of(9,7)))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> book(f,f.doctor(),LocalDate.of(1900,1,1),SLOT))
                .isInstanceOf(DataAccessException.class);
    }

    private Connection reader() throws SQLException {
        return DriverManager.getConnection(postgres.getJdbcUrl(),
                "aronexa_appointment_read_proof","disposable-appointment-proof-only");
    }
    private static void scope(Connection c,UUID tenant,UUID member) throws SQLException {
        try (PreparedStatement p=c.prepareStatement("""
                SELECT set_config('aronexa.tenant_id',?,true),
                       set_config('aronexa.membership_id',?,true)
                """)) {
            p.setString(1,tenant.toString());
            p.setString(2,member.toString());
            p.executeQuery().close();
        }
    }
    private static int readerCount(Connection c) throws SQLException {
        try(var p=c.prepareStatement("SELECT count(*) FROM appointment_proof.appointment");
            var r=p.executeQuery()){r.next();return r.getInt(1);}
    }

    @Test
    void restrictedSqlReaderHasNoPatientAccessOrAppointmentWrite() throws Exception {
        Clinic f=seed();
        book(f);
        try(Connection c=reader()){
            c.setAutoCommit(false);
            assertThat(readerCount(c)).isZero();
            scope(c,f.tenant(),f.receptionist());
            assertThat(readerCount(c)).isOne();
            assertThatThrownBy(()->{
                try(var p=c.prepareStatement("SELECT count(*) FROM public.patient")){
                    p.executeQuery().close();
                }
            }).isInstanceOf(SQLException.class);
            c.rollback();
            c.setAutoCommit(false);
            scope(c,f.tenant(),f.receptionist());
            assertThatThrownBy(()->{
                try(var p=c.prepareStatement("DELETE FROM appointment_proof.appointment")){
                    p.executeUpdate();
                }
            }).isInstanceOf(SQLException.class);
            c.rollback();
            c.setAutoCommit(false);
            assertThat(readerCount(c)).isZero();
            c.rollback();
        }
    }

    @Test
    void rlsCannotReadOtherBranchOrTenantOrUnassignedMembership() throws Exception {
        Clinic a=seed(),b=seed();
        book(a);book(b);
        try(Connection c=reader()){
            c.setAutoCommit(false);
            scope(c,a.tenant(),a.receptionist());
            assertThat(readerCount(c)).isOne();
            c.rollback();
            c.setAutoCommit(false);
            scope(c,a.tenant(),b.receptionist());
            assertThat(readerCount(c)).isZero();
            c.rollback();
            c.setAutoCommit(false);
            scope(c,b.tenant(),b.receptionist());
            assertThat(readerCount(c)).isOne();
            c.rollback();
        }
    }

    @Test
    void twoCompetingBookingsCommitExactlyOneSlot() throws Exception {
        Clinic f=seed();
        var gate=new CountDownLatch(1);
        var done=Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Callable<Boolean> competing=()->{
                gate.await(10,TimeUnit.SECONDS);
                try(Connection c=DriverManager.getConnection(postgres.getJdbcUrl(),
                        postgres.getUsername(),postgres.getPassword());
                    PreparedStatement p=c.prepareStatement("""
                        INSERT INTO appointment_proof.appointment
                        (id,tenant_id,branch_id,patient_id,doctor_membership_id,
                         booked_by_membership_id,updated_by_membership_id,visit_date,slot_start)
                        VALUES(?,?,?,?,?,?,?,?,?)
                        """)) {
                    p.setObject(1,UUID.randomUUID());
                    p.setObject(2,f.tenant());
                    p.setObject(3,f.branch());
                    p.setObject(4,f.patient());
                    p.setObject(5,f.doctor());
                    p.setObject(6,f.receptionist());
                    p.setObject(7,f.receptionist());
                    p.setObject(8,VISIT_DAY);
                    p.setObject(9,SLOT);
                    p.executeUpdate();
                    return true;
                } catch (SQLException duplicate) {
                    if (!"23505".equals(duplicate.getSQLState())) throw duplicate;
                    return false;
                }
            };
            var first=done.submit(competing);
            var second=done.submit(competing);
            gate.countDown();
            assertThat((first.get(20,TimeUnit.SECONDS)?1:0)
                    +(second.get(20,TimeUnit.SECONDS)?1:0)).isEqualTo(1);
        } finally {
            done.shutdownNow();
        }
        assertThat(admin.queryForObject("""
                SELECT count(*) FROM appointment_proof.appointment
                WHERE tenant_id=? AND branch_id=? AND doctor_membership_id=?
                """,Integer.class,f.tenant(),f.branch(),f.doctor())).isOne();
    }
}
