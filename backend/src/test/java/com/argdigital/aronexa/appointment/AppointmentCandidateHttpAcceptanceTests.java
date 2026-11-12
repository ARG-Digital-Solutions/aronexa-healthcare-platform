package com.argdigital.aronexa.appointment;

import com.argdigital.aronexa.auth.AuthPrincipal;
import com.argdigital.aronexa.auth.TenantAuthorization;
import com.argdigital.aronexa.encounter.EncounterDraftContract;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R1-04: genuinely committed appointment booking/check-in over HTTP with a
 * DB-backed bearer session and automatically destroyed PostgreSQL 17.
 *
 * IMPORTANT: This controller exists ONLY in src/test/java and requires the
 * test & appointment-candidate & !dev & !prod profile. Its datasource is the
 * disposable TEST migrator, NOT a production restricted writer. This is
 * intentionally a security-review candidate, NEVER deployable clinical API.
 * The reviewed production design still needs a dedicated least-privileged
 * SQL role, session-bound proof and separately authorized Flyway migration.
 */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties={"server.address=127.0.0.1","server.error.include-message=never"})
@ActiveProfiles({"test","appointment-candidate"})
@Import({AppointmentCandidateHttpAcceptanceTests.CandidateController.class,
        AppointmentCandidateHttpAcceptanceTests.DailyBillingController.class})
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AppointmentCandidateHttpAcceptanceTests {
    static final ZoneId CLINIC_ZONE=ZoneId.of("Asia/Kolkata");

    @Container @ServiceConnection
    static PostgreSQLContainer postgres=new PostgreSQLContainer("postgres:17-alpine")
            .withInitScript("db/test/role-bootstrap.sql")
            .withDatabaseName("aronexa_r104_disposable_only")
            .withUsername("aronexa_r104_disposable_only")
            .withPassword("temporary-r104-test-only");

    @Autowired JdbcTemplate admin;
    @Autowired PasswordEncoder passwords;
    @Autowired DataSource ownerDataSource;
    @Value("$"+"{local.server.port}") int port;
    final HttpClient http=HttpClient.newHttpClient();

    /** No production endpoint or Spring bean uses this controller. */
    @RestController
    @Profile("test & appointment-candidate & !dev & !prod")
    @RequestMapping("/api/v1/branches/{branchId}/appointments")
    static class CandidateController {
        private final TenantAuthorization access;
        private final JdbcTemplate jdbc;
        private final TransactionTemplate transactions;

        CandidateController(TenantAuthorization access,
                            @org.springframework.beans.factory.annotation.Qualifier(
                                    "applicationJdbcTemplate") JdbcTemplate jdbc,
                            @org.springframework.beans.factory.annotation.Qualifier(
                                    "transactionManager") PlatformTransactionManager manager) {
            this.access=access;
            this.jdbc=jdbc;
            this.transactions=new TransactionTemplate(manager);
        }

        record Book(UUID registrationId, UUID doctorMembershipId,
                    LocalDate visitDate, LocalTime slotStart, UUID requestId) {}
        record CheckIn(int expectedRevision) {}
        record Reschedule(UUID doctorMembershipId, LocalDate visitDate,
                          LocalTime slotStart, int expectedRevision) {}
        record Cancel(int expectedRevision) {}
        record SaveConsultation(int expectedRevision,String consultationNote) {}
        record ConsultationReceipt(UUID tenantId,UUID branchId,UUID patientId,
                UUID appointmentId,UUID doctorMembershipId,String stage,
                long revision,String consultationNote) {}
        record ConsultationContext(UUID tenantId,UUID branchId,UUID patientId,
                UUID appointmentId,UUID doctorMembershipId,String appointmentStage) {}
        record RxMedicine(UUID entryId,String medicine,String strength,
                String form,String dose,String route,String frequency,
                String duration,Integer quantity,String instructions) {}
        record SaveRx(int expectedRevision,int consultationRevision,
                List<RxMedicine> medicines) {}
        record RxReceipt(UUID tenantId,UUID branchId,UUID patientId,
                UUID appointmentId,UUID doctorMembershipId,String stage,
                int revision,int consultationRevision,List<RxMedicine> medicines) {}
        record BillCreate(UUID requestId,String serviceCode) {}
        record CashPost(UUID requestId,UUID invoiceId,Long amountPaise) {}
        record BillingContext(UUID tenantId,UUID branchId,UUID patientId,
                UUID appointmentId,UUID doctorMembershipId,
                String serviceCode,String serviceLabel,long amountPaise) {}
        record BillReceipt(UUID tenantId,UUID branchId,UUID patientId,
                UUID appointmentId,UUID doctorMembershipId,UUID invoiceId,
                long invoiceNumber,String serviceCode,String serviceLabel,
                long totalPaise,long paidPaise,long outstandingPaise,
                String status,boolean replayed) {}
        record BillingDaily(LocalDate date,UUID tenantId,UUID branchId,
                long invoiceCount,long billedPaise,long collectionsPaise,
                long outstandingPaise) {}
        record Receipt(UUID tenantId, UUID branchId, UUID patientId,
                       UUID registrationId, UUID appointmentId, UUID doctorMembershipId,
                       String stage, LocalDate visitDate, LocalTime slotStart,
                       int revision, boolean replayed) {}
        record Error(String code) {}
        static class Conflict extends RuntimeException {}

        private static void required(UUID id) {
            if(id==null || id.equals(new UUID(0,0)))throw new IllegalArgumentException();
        }

        private static boolean validSlot(LocalTime slot) {
            return slot!=null && slot.getSecond()==0 && slot.getNano()==0
                    && slot.getMinute()%15==0
                    && !slot.isBefore(LocalTime.of(9,0))
                    && !slot.isAfter(LocalTime.of(16,45));
        }

        private void requireCurrentSqlSession(AuthPrincipal actor, UUID branch,
                                               String permission) {
            required(branch);
            required(actor.sessionId());
            // Serializes a valid request with concurrent SQL logout and role/
            // membership/branch/module revocation. Merely trusting a caller
            // supplied tenant, SQL GUC, or Spring permission list is insufficient.
            var rows=jdbc.queryForList("""
                SELECT 1
                  FROM public.auth_session s
                  JOIN public.user_tenant_membership m
                    ON m.tenant_id=s.tenant_id AND m.id=s.membership_id
                  JOIN public.app_user u ON u.id=m.user_id
                  JOIN public.org_membership_branch_scope scope
                    ON scope.tenant_id=m.tenant_id AND scope.membership_id=m.id
                  JOIN public.auth_membership_role mr
                    ON mr.tenant_id=m.tenant_id AND mr.membership_id=m.id
                  JOIN public.auth_role_permission rp
                    ON rp.tenant_id=mr.tenant_id AND rp.role_id=mr.role_id
                  JOIN public.tenant_module_state ent
                    ON ent.tenant_id=m.tenant_id AND ent.module_code='OPD'
                  JOIN public.module_registry catalogue ON catalogue.code=ent.module_code
                 WHERE s.id=? AND s.tenant_id=? AND s.membership_id=?
                   AND s.revoked_at IS NULL AND s.expires_at>clock_timestamp()
                   AND m.enabled AND u.enabled AND NOT u.must_change_password
                   AND scope.branch_id=? AND rp.permission_code=?
                   AND ent.entitled AND ent.activated AND ent.clinically_authorized
                   AND catalogue.implemented AND catalogue.released
                 FOR SHARE OF s,m,u,scope,mr,rp,ent,catalogue
                """,Integer.class,actor.sessionId(),actor.tenantId(),
                    actor.membershipId(),branch,permission);
            if(rows.isEmpty())throw new AccessDeniedException("Appointment access denied");
        }

        private void requireDoctor(UUID tenant, UUID branch, UUID doctor) {
            required(doctor);
            var rows=jdbc.queryForList("""
                SELECT 1 FROM public.user_tenant_membership m
                JOIN public.app_user u ON u.id=m.user_id
                JOIN public.org_membership_branch_scope scope
                  ON scope.tenant_id=m.tenant_id AND scope.membership_id=m.id
                JOIN public.auth_membership_role mr
                  ON mr.tenant_id=m.tenant_id AND mr.membership_id=m.id
                JOIN public.auth_role role
                  ON role.tenant_id=mr.tenant_id AND role.id=mr.role_id
                WHERE m.tenant_id=? AND m.id=? AND scope.branch_id=?
                  AND m.enabled AND u.enabled AND role.code='DOCTOR'
                FOR SHARE OF m,u,scope,mr,role
                """,Integer.class,tenant,doctor,branch);
            if(rows.isEmpty())throw new AccessDeniedException("Assigned doctor denied");
        }

        private UUID registeredPatient(UUID tenant,UUID branch,UUID registration) {
            required(registration);
            var rows=jdbc.query("""
                SELECT r.patient_id FROM public.patient_registration r
                  JOIN public.patient p
                    ON p.tenant_id=r.tenant_id AND p.id=r.patient_id
                  JOIN public.patient_identifier identifier
                    ON identifier.tenant_id=p.tenant_id
                   AND identifier.patient_id=p.id AND identifier.identifier_type='MRN'
                 WHERE r.tenant_id=? AND r.branch_id=? AND r.id=?
                   AND p.status='ACTIVE'
                 FOR SHARE OF r,p,identifier
                """,(rs,i)->rs.getObject("patient_id",UUID.class),tenant,branch,registration);
            if(rows.isEmpty())throw new AccessDeniedException("Registration denied");
            return rows.getFirst();
        }

        private Receipt load(UUID tenant,UUID branch,UUID id,boolean replay) {
            var items=jdbc.query("""
                SELECT tenant_id,branch_id,patient_id,registration_id,id,
                       doctor_membership_id,status,visit_date,slot_start,revision
                  FROM appointment_proof.appointment
                 WHERE tenant_id=? AND branch_id=? AND id=?
                 FOR SHARE
                """,(rs,i)->new Receipt(rs.getObject("tenant_id",UUID.class),
                    rs.getObject("branch_id",UUID.class),
                    rs.getObject("patient_id",UUID.class),
                    rs.getObject("registration_id",UUID.class),
                    rs.getObject("id",UUID.class),
                    rs.getObject("doctor_membership_id",UUID.class),
                    rs.getString("status"),rs.getObject("visit_date",LocalDate.class),
                    rs.getObject("slot_start",LocalTime.class),
                    rs.getInt("revision"),replay),tenant,branch,id);
            return items.isEmpty()?null:items.getFirst();
        }

        @PostMapping
        ResponseEntity<Receipt> book(@PathVariable UUID branchId,@RequestBody Book input) {
            if(input==null)throw new IllegalArgumentException();
            required(input.requestId());
            required(input.registrationId());
            required(input.doctorMembershipId());
            if(input.visitDate()==null
                    || input.visitDate().isBefore(LocalDate.now(CLINIC_ZONE))
                    || input.visitDate().isAfter(LocalDate.now(CLINIC_ZONE).plusDays(90))
                    || !validSlot(input.slotStart()))throw new IllegalArgumentException();
            AuthPrincipal actor=access.requirePermission("APPOINTMENT_BOOK");
            Receipt receipt;
            try {
                receipt=transactions.execute(tx->{
                    requireCurrentSqlSession(actor,branchId,"APPOINTMENT_BOOK");
                    UUID patient=registeredPatient(actor.tenantId(),branchId,input.registrationId());
                    requireDoctor(actor.tenantId(),branchId,input.doctorMembershipId());
                    var old=jdbc.query("""
                        SELECT id FROM appointment_proof.appointment
                         WHERE tenant_id=? AND booking_request_id=?
                           AND booked_by_membership_id=?
                         FOR UPDATE
                        """,(rs,i)->rs.getObject(1,UUID.class),
                        actor.tenantId(),input.requestId(),actor.membershipId());
                    if(!old.isEmpty()) {
                        Receipt previous=load(actor.tenantId(),branchId,old.getFirst(),true);
                        if(previous==null || !previous.patientId().equals(patient)
                           || !previous.registrationId().equals(input.registrationId())
                           || !previous.doctorMembershipId().equals(input.doctorMembershipId())
                           || !previous.visitDate().equals(input.visitDate())
                           || !previous.slotStart().equals(input.slotStart()))throw new Conflict();
                        return previous;
                    }
                    UUID id=UUID.randomUUID();
                    jdbc.update("""
                        INSERT INTO appointment_proof.appointment
                        (id,tenant_id,branch_id,patient_id,registration_id,doctor_membership_id,
                         booked_by_membership_id,updated_by_membership_id,visit_date,slot_start,
                         booking_request_id)
                        VALUES (?,?,?,?,?,?,?,?,?,?,?)
                        """,id,actor.tenantId(),branchId,patient,input.registrationId(),
                            input.doctorMembershipId(),actor.membershipId(),actor.membershipId(),
                            input.visitDate(),input.slotStart(),input.requestId());
                    return load(actor.tenantId(),branchId,id,false);
                });
            } catch(DataIntegrityViolationException failure) {
                // Slot or request collision: never expose SQL/identifier values.
                throw new Conflict();
            }
            return ResponseEntity.status(receipt.replayed()?HttpStatus.OK:HttpStatus.CREATED)
                    .cacheControl(CacheControl.noStore()).body(receipt);
        }

        @GetMapping("/{appointmentId}")
        ResponseEntity<Receipt> read(@PathVariable UUID branchId,
                                     @PathVariable UUID appointmentId) {
            required(appointmentId);
            AuthPrincipal actor=access.requirePermission("APPOINTMENT_READ");
            Receipt result=transactions.execute(tx->{
                requireCurrentSqlSession(actor,branchId,"APPOINTMENT_READ");
                Receipt found=load(actor.tenantId(),branchId,appointmentId,false);
                if(found!=null)jdbc.update("""
                    INSERT INTO appointment_proof.read_audit
                      (tenant_id,branch_id,appointment_id,actor_membership_id)
                    VALUES (?,?,?,?)
                    """,actor.tenantId(),branchId,appointmentId,actor.membershipId());
                return found;
            });
            return result==null
                    ?ResponseEntity.notFound().cacheControl(CacheControl.noStore()).build()
                    :ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
        }

        @PostMapping("/{appointmentId}/check-in")
        ResponseEntity<Receipt> checkIn(@PathVariable UUID branchId,
                                        @PathVariable UUID appointmentId,
                                        @RequestBody CheckIn input) {
            required(appointmentId);
            if(input==null || input.expectedRevision()<1)throw new IllegalArgumentException();
            AuthPrincipal actor=access.requirePermission("APPOINTMENT_CHECK_IN");
            Receipt result=transactions.execute(tx->{
                requireCurrentSqlSession(actor,branchId,"APPOINTMENT_CHECK_IN");
                Receipt old=load(actor.tenantId(),branchId,appointmentId,false);
                if(old==null)throw new AccessDeniedException("Appointment unavailable");
                if(!old.stage().equals("BOOKED")
                        || old.revision()!=input.expectedRevision()
                        || !old.visitDate().equals(LocalDate.now(CLINIC_ZONE)))
                    throw new Conflict();
                int changed=jdbc.update("""
                    UPDATE appointment_proof.appointment
                       SET status='CHECKED_IN',revision=revision+1,
                           updated_by_membership_id=?
                     WHERE tenant_id=? AND branch_id=? AND id=?
                       AND status='BOOKED' AND revision=?
                    """,actor.membershipId(),actor.tenantId(),branchId,
                        appointmentId,input.expectedRevision());
                if(changed!=1)throw new Conflict();
                return load(actor.tenantId(),branchId,appointmentId,false);
            });
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
        }

        private static boolean withinVisitWindow(LocalDate day) {
            LocalDate now=LocalDate.now(CLINIC_ZONE);
            return day!=null && !day.isBefore(now) && !day.isAfter(now.plusDays(90));
        }

        /**
         * Daily queue is a fresh branch-scoped SQL read, never a browser cache.
         * Do not silently truncate a busy clinic's queue: 101 rows -> conflict.
         * Excludes cancelled appointments, but retains them for UUID lookup.
         */
        @GetMapping
        ResponseEntity<List<Receipt>> dailyQueue(
                @PathVariable UUID branchId,
                @RequestParam("visitDate")
                @org.springframework.format.annotation.DateTimeFormat(
                    iso=org.springframework.format.annotation.DateTimeFormat.ISO.DATE)
                LocalDate visitDate) {
            if(visitDate==null
                    ||visitDate.isBefore(LocalDate.now(CLINIC_ZONE).minusDays(365))
                    ||visitDate.isAfter(LocalDate.now(CLINIC_ZONE).plusDays(90)))
                throw new IllegalArgumentException();
            AuthPrincipal actor=access.requirePermission("APPOINTMENT_READ");
            List<Receipt> records=transactions.execute(tx->{
                requireCurrentSqlSession(actor,branchId,"APPOINTMENT_READ");
                var rows=jdbc.query("""
                    SELECT tenant_id,branch_id,patient_id,registration_id,id,
                           doctor_membership_id,status,visit_date,slot_start,revision
                      FROM appointment_proof.appointment
                     WHERE tenant_id=? AND branch_id=? AND visit_date=?
                       AND status IN ('BOOKED','CHECKED_IN')
                     ORDER BY slot_start,id
                     LIMIT 101
                     FOR SHARE
                    """,(rs,i)->new Receipt(rs.getObject("tenant_id",UUID.class),
                    rs.getObject("branch_id",UUID.class),
                    rs.getObject("patient_id",UUID.class),
                    rs.getObject("registration_id",UUID.class),
                    rs.getObject("id",UUID.class),
                    rs.getObject("doctor_membership_id",UUID.class),
                    rs.getString("status"),rs.getObject("visit_date",LocalDate.class),
                    rs.getObject("slot_start",LocalTime.class),
                    rs.getInt("revision"),false),
                    actor.tenantId(),branchId,visitDate);
                if(rows.size()>100)throw new Conflict();
                for(Receipt item:rows) {
                    jdbc.update("""
                        INSERT INTO appointment_proof.read_audit
                          (tenant_id,branch_id,appointment_id,actor_membership_id)
                        VALUES (?,?,?,?)
                        """,actor.tenantId(),branchId,item.appointmentId(),
                            actor.membershipId());
                }
                return List.copyOf(rows);
            });
            return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                    .body(records);
        }

        @PostMapping("/{appointmentId}/reschedule")
        ResponseEntity<Receipt> reschedule(
                @PathVariable UUID branchId,@PathVariable UUID appointmentId,
                @RequestBody Reschedule input) {
            required(appointmentId);
            if(input==null || input.expectedRevision()<1
                    ||!withinVisitWindow(input.visitDate())
                    ||!validSlot(input.slotStart()))
                throw new IllegalArgumentException();
            required(input.doctorMembershipId());
            AuthPrincipal actor=access.requirePermission("APPOINTMENT_RESCHEDULE");
            Receipt updated;
            try {
                updated=transactions.execute(tx->{
                    requireCurrentSqlSession(actor,branchId,"APPOINTMENT_RESCHEDULE");
                    Receipt current=load(actor.tenantId(),branchId,appointmentId,false);
                    if(current==null)throw new AccessDeniedException("Appointment denied");
                    if(!"BOOKED".equals(current.stage())
                       ||current.revision()!=input.expectedRevision()
                       ||(current.doctorMembershipId().equals(input.doctorMembershipId())
                          &&current.visitDate().equals(input.visitDate())
                          &&current.slotStart().equals(input.slotStart())))
                        throw new Conflict();
                    requireDoctor(actor.tenantId(),branchId,input.doctorMembershipId());
                    int changed=jdbc.update("""
                        UPDATE appointment_proof.appointment
                           SET doctor_membership_id=?,visit_date=?,slot_start=?,
                               revision=revision+1,updated_by_membership_id=?
                         WHERE id=? AND tenant_id=? AND branch_id=?
                           AND status='BOOKED' AND revision=?
                        """,input.doctorMembershipId(),input.visitDate(),
                            input.slotStart(),actor.membershipId(),appointmentId,
                            actor.tenantId(),branchId,input.expectedRevision());
                    if(changed!=1)throw new Conflict();
                    return load(actor.tenantId(),branchId,appointmentId,false);
                });
            } catch(DataIntegrityViolationException failure) {
                // Database-enforced doctor-slot uniqueness; no SQL values leaked.
                throw new Conflict();
            }
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(updated);
        }

        @PostMapping("/{appointmentId}/cancel")
        ResponseEntity<Receipt> cancel(
                @PathVariable UUID branchId,@PathVariable UUID appointmentId,
                @RequestBody Cancel input) {
            required(appointmentId);
            if(input==null || input.expectedRevision()<1)
                throw new IllegalArgumentException();
            AuthPrincipal actor=access.requirePermission("APPOINTMENT_CANCEL");
            Receipt cancelled=transactions.execute(tx->{
                requireCurrentSqlSession(actor,branchId,"APPOINTMENT_CANCEL");
                Receipt current=load(actor.tenantId(),branchId,appointmentId,false);
                if(current==null)throw new AccessDeniedException("Appointment denied");
                if(!"BOOKED".equals(current.stage())
                        ||current.revision()!=input.expectedRevision())
                    throw new Conflict();
                int changed=jdbc.update("""
                    UPDATE appointment_proof.appointment
                       SET status='CANCELLED',revision=revision+1,
                           updated_by_membership_id=?
                     WHERE id=? AND tenant_id=? AND branch_id=?
                       AND status='BOOKED' AND revision=?
                    """,actor.membershipId(),appointmentId,actor.tenantId(),branchId,
                    input.expectedRevision());
                if(changed!=1)throw new Conflict();
                return load(actor.tenantId(),branchId,appointmentId,false);
            });
            return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                    .body(cancelled);
        }


        private Receipt requireDoctorCheckedIn(AuthPrincipal doctor,
                                                 UUID branchId,UUID appointmentId) {
            requireCurrentSqlSession(doctor,branchId,"OPD_DRAFT_WRITE");
            requireDoctor(doctor.tenantId(),branchId,doctor.membershipId());
            Receipt appointment=load(doctor.tenantId(),branchId,appointmentId,false);
            if(appointment==null
                    ||!doctor.membershipId().equals(appointment.doctorMembershipId()))
                throw new AccessDeniedException("Consultation scope denied");
            if(!"CHECKED_IN".equals(appointment.stage()))throw new Conflict();
            return appointment;
        }

        private EncounterDraftContract.Draft currentConsultation(Receipt visit) {
            var scope=new EncounterDraftContract.Scope(visit.tenantId(),
                    visit.branchId(),visit.patientId(),visit.appointmentId(),
                    visit.doctorMembershipId());
            var notes=jdbc.query("""
                SELECT revision,consultation_note FROM appointment_proof.consultation_revision
                 WHERE tenant_id=? AND branch_id=? AND appointment_id=?
                 ORDER BY revision DESC LIMIT 1
                """,(rs,i)->new EncounterDraftContract.Draft(scope,
                    EncounterDraftContract.Stage.CONSULTATION_DRAFT,
                    rs.getInt("revision"),rs.getString("consultation_note"),
                    null,null),
                visit.tenantId(),visit.branchId(),visit.appointmentId());
            return notes.isEmpty()?EncounterDraftContract.checkedIn(scope):notes.getFirst();
        }

        private static ConsultationReceipt consultationReceipt(
                EncounterDraftContract.Draft state) {
            var scope=state.scope();
            return new ConsultationReceipt(scope.tenantId(),scope.branchId(),
                    scope.patientId(),scope.encounterId(),scope.doctorMembershipId(),
                    state.stage().name(),state.revision(),state.consultationNote());
        }

        /**
         * A doctor-only independent checked-in appointment identity lookup.
         * Neither the client nor the consultation response supplies trusted
         * patient identity. This endpoint re-reads the assigned appointment.
         */
        @GetMapping("/{appointmentId}/consultation-context")
        ResponseEntity<ConsultationContext> consultationContext(
                @PathVariable UUID branchId,@PathVariable UUID appointmentId) {
            required(appointmentId);
            AuthPrincipal actor=access.requirePermission("OPD_DRAFT_WRITE");
            ConsultationContext result=transactions.execute(tx->{
                var visit=requireDoctorCheckedIn(actor,branchId,appointmentId);
                jdbc.update("""
                    INSERT INTO appointment_proof.consultation_read_audit
                      (appointment_id,tenant_id,branch_id,actor_membership_id)
                    VALUES (?,?,?,?)
                    """,appointmentId,actor.tenantId(),branchId,actor.membershipId());
                return new ConsultationContext(visit.tenantId(),visit.branchId(),
                    visit.patientId(),visit.appointmentId(),
                    visit.doctorMembershipId(),visit.stage());
            });
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
        }

        @GetMapping("/{appointmentId}/consultation-draft")
        ResponseEntity<ConsultationReceipt> readConsultation(
                @PathVariable UUID branchId,@PathVariable UUID appointmentId) {
            required(appointmentId);
            AuthPrincipal actor=access.requirePermission("OPD_DRAFT_WRITE");
            ConsultationReceipt result=transactions.execute(tx->{
                var visit=requireDoctorCheckedIn(actor,branchId,appointmentId);
                var state=currentConsultation(visit);
                jdbc.update("""
                    INSERT INTO appointment_proof.consultation_read_audit
                      (appointment_id,tenant_id,branch_id,actor_membership_id)
                    VALUES (?,?,?,?)
                    """,appointmentId,actor.tenantId(),branchId,actor.membershipId());
                return consultationReceipt(state);
            });
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
        }

        @PostMapping("/{appointmentId}/consultation-draft")
        ResponseEntity<ConsultationReceipt> saveConsultation(
                @PathVariable UUID branchId,@PathVariable UUID appointmentId,
                @RequestBody SaveConsultation input) {
            required(appointmentId);
            if(input==null||input.expectedRevision()<0
                    ||input.consultationNote()==null
                    ||input.consultationNote().isBlank()
                    ||input.consultationNote().length()>2000)
                throw new IllegalArgumentException();
            AuthPrincipal actor=access.requirePermission("OPD_DRAFT_WRITE");
            ConsultationReceipt result;
            try {
                result=transactions.execute(tx->{
                    requireCurrentSqlSession(actor,branchId,"OPD_DRAFT_WRITE");
                    requireDoctor(actor.tenantId(),branchId,actor.membershipId());
                    // Row lock serializes drafts even before revision 1 exists.
                    // Never trust caller-supplied patient/doctor/tenant scope.
                    var locked=jdbc.query("""
                        SELECT id FROM appointment_proof.appointment
                         WHERE tenant_id=? AND branch_id=? AND id=?
                           AND doctor_membership_id=?
                         FOR UPDATE
                        """,(rs,i)->rs.getObject(1,UUID.class),
                        actor.tenantId(),branchId,appointmentId,actor.membershipId());
                    if(locked.isEmpty())
                        throw new AccessDeniedException("Consultation scope denied");
                    Receipt visit=requireDoctorCheckedIn(actor,branchId,appointmentId);
                    var current=currentConsultation(visit);
                    if(current.revision()!=input.expectedRevision())
                        throw new Conflict();
                    var updated=EncounterDraftContract.saveConsultation(current,
                            input.expectedRevision(),input.consultationNote());
                    jdbc.update("""
                        INSERT INTO appointment_proof.consultation_revision
                          (appointment_id,tenant_id,branch_id,patient_id,
                           doctor_membership_id,revision,consultation_note,
                           actor_membership_id)
                        VALUES (?,?,?,?,?,?,?,?)
                        """,appointmentId,actor.tenantId(),branchId,visit.patientId(),
                            actor.membershipId(),updated.revision(),
                            updated.consultationNote(),actor.membershipId());
                    return consultationReceipt(updated);
                });
            } catch(DataIntegrityViolationException rejected) {
                // Competing revision/constraint. Never return SQL or note text.
                throw new Conflict();
            }
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
        }


        private static String rxText(String value,int max,boolean required) {
            if(value==null||value.length()>max||value.indexOf('\u0000')>=0)
                throw new IllegalArgumentException();
            String trimmed=value.strip();
            if(required&&trimmed.isEmpty())throw new IllegalArgumentException();
            return trimmed;
        }

        private static List<RxMedicine> normalizeRx(List<RxMedicine> medicines) {
            if(medicines==null||medicines.isEmpty()||medicines.size()>30)
                throw new IllegalArgumentException();
            java.util.Set<UUID> ids=new java.util.HashSet<>();
            List<RxMedicine> normalized=new java.util.ArrayList<>();
            for(RxMedicine item:medicines) {
                if(item==null||item.entryId()==null
                    ||item.entryId().equals(new UUID(0,0))
                    ||!ids.add(item.entryId())
                    ||item.quantity()==null||item.quantity()<1
                    ||item.quantity()>10000)
                    throw new IllegalArgumentException();
                normalized.add(new RxMedicine(item.entryId(),
                    rxText(item.medicine(),120,true),
                    rxText(item.strength(),80,true),
                    rxText(item.form(),50,true),
                    rxText(item.dose(),120,true),
                    rxText(item.route(),60,true),
                    rxText(item.frequency(),100,true),
                    rxText(item.duration(),100,true),
                    item.quantity(),rxText(item.instructions(),500,false)));
            }
            return List.copyOf(normalized);
        }

        private List<RxMedicine> rxLines(UUID appointment,int revision) {
            if(revision==0)return List.of();
            return jdbc.query("""
                SELECT entry_id,medicine,strength,form,dose,route,
                       frequency,duration,quantity,instructions
                  FROM appointment_proof.rx_draft_medicine
                 WHERE appointment_id=? AND revision=?
                 ORDER BY line_order
                """,(rs,i)->new RxMedicine(rs.getObject("entry_id",UUID.class),
                    rs.getString("medicine"),rs.getString("strength"),
                    rs.getString("form"),rs.getString("dose"),
                    rs.getString("route"),rs.getString("frequency"),
                    rs.getString("duration"),rs.getInt("quantity"),
                    rs.getString("instructions")),appointment,revision);
        }

        private RxReceipt currentRx(Receipt visit,long consultationRevision) {
            var versions=jdbc.query("""
                SELECT revision,consultation_revision
                  FROM appointment_proof.rx_draft_revision
                 WHERE tenant_id=? AND branch_id=? AND appointment_id=?
                 ORDER BY revision DESC LIMIT 1
                """,(rs,i)->new int[]{rs.getInt(1),rs.getInt(2)},
                visit.tenantId(),visit.branchId(),visit.appointmentId());
            int revision=versions.isEmpty()?0:versions.getFirst()[0];
            int linked=versions.isEmpty()?(int)consultationRevision:versions.getFirst()[1];
            // A changed consultation requires explicit reconciliation. Do not
            // carry a previous medication snapshot over to a new note silently.
            if(linked!=consultationRevision)throw new Conflict();
            return new RxReceipt(visit.tenantId(),visit.branchId(),
                    visit.patientId(),visit.appointmentId(),
                    visit.doctorMembershipId(),
                    revision==0?"EMPTY_DRAFT":"RX_DRAFT",revision,linked,
                    List.copyOf(rxLines(visit.appointmentId(),revision)));
        }

        private Receipt requireRxAccess(AuthPrincipal actor,UUID branchId,
                                        UUID appointmentId) {
            requireCurrentSqlSession(actor,branchId,"OPD_RX_DRAFT_WRITE");
            Receipt visit=requireDoctorCheckedIn(actor,branchId,appointmentId);
            if(currentConsultation(visit).stage()
                    !=EncounterDraftContract.Stage.CONSULTATION_DRAFT)
                throw new Conflict();
            return visit;
        }

        /** Doctor-only, no-store structured Rx DRAFT. Not an issued order. */
        @GetMapping("/{appointmentId}/prescription-draft")
        ResponseEntity<RxReceipt> readRx(
                @PathVariable UUID branchId,@PathVariable UUID appointmentId) {
            required(appointmentId);
            AuthPrincipal actor=access.requirePermission("OPD_RX_DRAFT_WRITE");
            RxReceipt result=transactions.execute(tx->{
                Receipt visit=requireRxAccess(actor,branchId,appointmentId);
                long consultationRevision=currentConsultation(visit).revision();
                RxReceipt found=currentRx(visit,consultationRevision);
                jdbc.update("""
                    INSERT INTO appointment_proof.rx_draft_read_audit
                      (appointment_id,tenant_id,branch_id,actor_membership_id)
                    VALUES(?,?,?,?)
                    """,appointmentId,actor.tenantId(),branchId,actor.membershipId());
                return found;
            });
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
        }

        @PostMapping("/{appointmentId}/prescription-draft")
        ResponseEntity<RxReceipt> saveRx(
                @PathVariable UUID branchId,@PathVariable UUID appointmentId,
                @RequestBody SaveRx input) {
            required(appointmentId);
            if(input==null||input.expectedRevision()<0
                    ||input.consultationRevision()<1)
                throw new IllegalArgumentException();
            List<RxMedicine> normalized=normalizeRx(input.medicines());
            AuthPrincipal actor=access.requirePermission("OPD_RX_DRAFT_WRITE");
            RxReceipt result;
            try {
                result=transactions.execute(tx->{
                    requireCurrentSqlSession(actor,branchId,"OPD_RX_DRAFT_WRITE");
                    var locked=jdbc.query("""
                        SELECT id FROM appointment_proof.appointment
                         WHERE tenant_id=? AND branch_id=? AND id=?
                           AND doctor_membership_id=? AND status='CHECKED_IN'
                         FOR UPDATE
                        """,(rs,i)->rs.getObject(1,UUID.class),
                        actor.tenantId(),branchId,appointmentId,actor.membershipId());
                    if(locked.isEmpty())
                        throw new AccessDeniedException("Prescription scope denied");
                    Receipt visit=requireRxAccess(actor,branchId,appointmentId);
                    int consultRev=(int)currentConsultation(visit).revision();
                    if(consultRev!=input.consultationRevision())
                        throw new Conflict();
                    RxReceipt current=currentRx(visit,consultRev);
                    if(current.revision()!=input.expectedRevision()
                       ||current.revision()>=1_000_000_000)
                        throw new Conflict();
                    int revision=current.revision()+1;
                    jdbc.update("""
                        INSERT INTO appointment_proof.rx_draft_revision
                          (appointment_id,tenant_id,branch_id,patient_id,
                           doctor_membership_id,revision,consultation_revision,
                           actor_membership_id)
                        VALUES(?,?,?,?,?,?,?,?)
                        """,appointmentId,actor.tenantId(),branchId,visit.patientId(),
                        actor.membershipId(),revision,consultRev,actor.membershipId());
                    int position=0;
                    for(RxMedicine m:normalized) {
                        jdbc.update("""
                            INSERT INTO appointment_proof.rx_draft_medicine
                              (appointment_id,revision,line_order,entry_id,
                               medicine,strength,form,dose,route,frequency,
                               duration,quantity,instructions)
                            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
                            """,appointmentId,revision,++position,m.entryId(),
                            m.medicine(),m.strength(),m.form(),m.dose(),
                            m.route(),m.frequency(),m.duration(),
                            m.quantity(),m.instructions());
                    }
                    return currentRx(visit,consultRev);
                });
            } catch(DataIntegrityViolationException failure) {
                throw new Conflict();
            }
            return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                    .body(result);
        }


        private void requireBillingActor(AuthPrincipal actor,UUID branchId,
                                         String permission) {
            requireCurrentSqlSession(actor,branchId,permission);
            var roles=jdbc.queryForList("""
                SELECT 1 FROM public.auth_membership_role mr
                JOIN public.auth_role role ON role.id=mr.role_id
                 AND role.tenant_id=mr.tenant_id
                WHERE mr.tenant_id=? AND mr.membership_id=?
                  AND role.code='BILLING'
                FOR SHARE OF mr,role
                """,Integer.class,actor.tenantId(),actor.membershipId());
            if(roles.isEmpty())
                throw new AccessDeniedException("Billing access denied");
        }

        private Receipt billableVisit(AuthPrincipal actor,UUID branchId,
                                      UUID appointmentId,String permission) {
            requireBillingActor(actor,branchId,permission);
            Receipt visit=load(actor.tenantId(),branchId,appointmentId,false);
            if(visit==null)
                throw new AccessDeniedException("Billing access denied");
            if(!"CHECKED_IN".equals(visit.stage())
                ||currentConsultation(visit).stage()
                    !=EncounterDraftContract.Stage.CONSULTATION_DRAFT)
                throw new Conflict();
            return visit;
        }

        private BillingContext feeContext(Receipt visit) {
            var fees=jdbc.query("""
                SELECT service_code,service_label,amount_paise
                 FROM appointment_proof.fee_schedule
                WHERE tenant_id=? AND branch_id=?
                  AND service_code='OPD-CONSULTATION' AND enabled=true
                """,(rs,i)->new BillingContext(visit.tenantId(),
                    visit.branchId(),visit.patientId(),visit.appointmentId(),
                    visit.doctorMembershipId(),rs.getString(1),
                    rs.getString(2),rs.getLong(3)),
                visit.tenantId(),visit.branchId());
            if(fees.size()!=1)throw new Conflict();
            return fees.getFirst();
        }

        private BillReceipt invoiceReceipt(UUID tenant,UUID branch,UUID appointment,
                                           boolean replayed) {
            var found=jdbc.query("""
                SELECT id,invoice_number,patient_id,doctor_membership_id,
                  service_code,service_label,total_paise,
                  COALESCE((SELECT SUM(amount_paise)
                    FROM appointment_proof.opd_cash_event e
                    WHERE e.invoice_id=i.id AND e.tenant_id=i.tenant_id
                      AND e.branch_id=i.branch_id),0) AS paid_paise
                FROM appointment_proof.opd_invoice i
                WHERE tenant_id=? AND branch_id=? AND appointment_id=?
                """,(rs,i)->{
                    long amount=rs.getLong("total_paise"),
                         paid=rs.getLong("paid_paise");
                    long outstanding=amount-paid;
                    if(outstanding<0)throw new Conflict();
                    return new BillReceipt(tenant,branch,
                        rs.getObject("patient_id",UUID.class),appointment,
                        rs.getObject("doctor_membership_id",UUID.class),
                        rs.getObject("id",UUID.class),
                        rs.getLong("invoice_number"),
                        rs.getString("service_code"),
                        rs.getString("service_label"),amount,paid,
                        outstanding,paid==0?"UNPAID":
                        outstanding==0?"PAID":"PARTIAL",replayed);
                },tenant,branch,appointment);
            return found.isEmpty()?null:found.getFirst();
        }

        @GetMapping("/{appointmentId}/billing-context")
        ResponseEntity<BillingContext> readBillingContext(
                @PathVariable UUID branchId,@PathVariable UUID appointmentId) {
            required(appointmentId);
            AuthPrincipal actor=access.requirePermission("OPD_BILL_READ");
            BillingContext result=transactions.execute(tx->{
                Receipt visit=billableVisit(actor,branchId,appointmentId,
                        "OPD_BILL_READ");
                BillingContext context=feeContext(visit);
                jdbc.update("""
                    INSERT INTO appointment_proof.opd_billing_read_audit
                      (tenant_id,branch_id,actor_membership_id,appointment_id)
                    VALUES(?,?,?,?)
                    """,actor.tenantId(),branchId,actor.membershipId(),appointmentId);
                return context;
            });
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
        }

        @GetMapping("/{appointmentId}/bill")
        ResponseEntity<BillReceipt> readBill(
                @PathVariable UUID branchId,@PathVariable UUID appointmentId) {
            required(appointmentId);
            AuthPrincipal actor=access.requirePermission("OPD_BILL_READ");
            BillReceipt result=transactions.execute(tx->{
                Receipt visit=billableVisit(actor,branchId,appointmentId,
                        "OPD_BILL_READ");
                BillReceipt existing=invoiceReceipt(actor.tenantId(),
                        branchId,appointmentId,false);
                if(existing!=null&&
                   !visit.patientId().equals(existing.patientId()))
                    throw new AccessDeniedException("Billing scope denied");
                jdbc.update("""
                    INSERT INTO appointment_proof.opd_billing_read_audit
                      (tenant_id,branch_id,actor_membership_id,appointment_id)
                    VALUES(?,?,?,?)
                    """,actor.tenantId(),branchId,actor.membershipId(),appointmentId);
                return existing;
            });
            return result==null
                    ?ResponseEntity.status(404).cacheControl(CacheControl.noStore()).build()
                    :ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
        }

        @PostMapping("/{appointmentId}/bill")
        ResponseEntity<BillReceipt> createBill(
                @PathVariable UUID branchId,@PathVariable UUID appointmentId,
                @RequestBody BillCreate input) {
            required(appointmentId);
            if(input==null)throw new IllegalArgumentException();
            required(input.requestId());
            if(!"OPD-CONSULTATION".equals(input.serviceCode()))
                throw new IllegalArgumentException();
            AuthPrincipal actor=access.requirePermission("OPD_BILL_CREATE");
            BillReceipt result;
            try {
                result=transactions.execute(tx->{
                    requireBillingActor(actor,branchId,"OPD_BILL_CREATE");
                    var locks=jdbc.query("""
                        SELECT id FROM appointment_proof.appointment
                        WHERE tenant_id=? AND branch_id=? AND id=?
                          AND status='CHECKED_IN'
                        FOR UPDATE
                        """,(rs,i)->rs.getObject(1,UUID.class),
                        actor.tenantId(),branchId,appointmentId);
                    if(locks.isEmpty())throw new Conflict();
                    Receipt visit=billableVisit(actor,branchId,appointmentId,
                            "OPD_BILL_CREATE");
                    BillingContext fee=feeContext(visit);
                    BillReceipt previous=invoiceReceipt(actor.tenantId(),
                            branchId,appointmentId,true);
                    if(previous!=null) {
                        var requests=jdbc.query("""
                            SELECT invoice_request_id FROM appointment_proof.opd_invoice
                            WHERE tenant_id=? AND branch_id=? AND appointment_id=?
                            """,(rs,i)->rs.getObject(1,UUID.class),
                            actor.tenantId(),branchId,appointmentId);
                        if(requests.size()==1
                            &&input.requestId().equals(requests.getFirst())
                            &&input.serviceCode().equals(previous.serviceCode()))
                            return previous;
                        throw new Conflict();
                    }
                    jdbc.update("""
                        INSERT INTO appointment_proof.opd_invoice
                          (id,invoice_request_id,tenant_id,branch_id,appointment_id,
                           patient_id,doctor_membership_id,service_code,
                           service_label,total_paise,actor_membership_id)
                        VALUES(?,?,?,?,?,?,?,?,?,?,?)
                        """,UUID.randomUUID(),input.requestId(),actor.tenantId(),
                        branchId,appointmentId,visit.patientId(),
                        visit.doctorMembershipId(),fee.serviceCode(),
                        fee.serviceLabel(),fee.amountPaise(),actor.membershipId());
                    return invoiceReceipt(actor.tenantId(),branchId,appointmentId,false);
                });
            }catch(DataIntegrityViolationException denied){throw new Conflict();}
            return ResponseEntity.status(result.replayed()?HttpStatus.OK:HttpStatus.CREATED)
                    .cacheControl(CacheControl.noStore()).body(result);
        }

        @PostMapping("/{appointmentId}/bill/cash")
        ResponseEntity<BillReceipt> recordCash(
                @PathVariable UUID branchId,@PathVariable UUID appointmentId,
                @RequestBody CashPost input) {
            required(appointmentId);
            if(input==null)throw new IllegalArgumentException();
            required(input.requestId());
            required(input.invoiceId());
            if(input.amountPaise()==null||input.amountPaise()<1
                    ||input.amountPaise()>10_000_000_000L)
                throw new IllegalArgumentException();
            AuthPrincipal actor=access.requirePermission("OPD_PAYMENT_POST");
            BillReceipt result;
            try{
                result=transactions.execute(tx->{
                    requireBillingActor(actor,branchId,"OPD_PAYMENT_POST");
                    // The invoice row lock serializes cash postings and ensures
                    // concurrent requests cannot overcollect the same invoice.
                    var locked=jdbc.query("""
                        SELECT id FROM appointment_proof.opd_invoice
                         WHERE tenant_id=? AND branch_id=? AND appointment_id=?
                           AND id=? FOR UPDATE
                        """,(rs,i)->rs.getObject(1,UUID.class),
                        actor.tenantId(),branchId,appointmentId,input.invoiceId());
                    if(locked.isEmpty())
                        throw new AccessDeniedException("Billing access denied");
                    Receipt visit=billableVisit(actor,branchId,appointmentId,
                            "OPD_PAYMENT_POST");
                    BillReceipt before=invoiceReceipt(actor.tenantId(),
                            branchId,appointmentId,false);
                    if(before==null||!before.patientId().equals(visit.patientId()))
                        throw new AccessDeniedException("Billing scope denied");
                    var previous=jdbc.query("""
                        SELECT invoice_id,amount_paise
                        FROM appointment_proof.opd_cash_event
                        WHERE tenant_id=? AND payment_request_id=?
                        """,(rs,i)->new Object[]{
                            rs.getObject(1,UUID.class),rs.getLong(2)},
                        actor.tenantId(),input.requestId());
                    if(!previous.isEmpty()){
                        Object[] item=previous.getFirst();
                        if(input.invoiceId().equals(item[0])
                          &&input.amountPaise().equals(item[1]))
                            return invoiceReceipt(actor.tenantId(),
                                    branchId,appointmentId,true);
                        throw new Conflict();
                    }
                    if(input.amountPaise()>before.outstandingPaise())
                        throw new Conflict();
                    jdbc.update("""
                        INSERT INTO appointment_proof.opd_cash_event
                          (id,tenant_id,branch_id,invoice_id,payment_request_id,
                           amount_paise,actor_membership_id)
                        VALUES(?,?,?,?,?,?,?)
                        """,UUID.randomUUID(),actor.tenantId(),branchId,
                        input.invoiceId(),input.requestId(),
                        input.amountPaise(),actor.membershipId());
                    return invoiceReceipt(actor.tenantId(),branchId,appointmentId,false);
                });
            }catch(DataIntegrityViolationException denied){throw new Conflict();}
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
        }

        ResponseEntity<BillingDaily> dailyReport(UUID branchId,LocalDate day) {
            required(branchId);
            if(day==null||day.isBefore(LocalDate.now(CLINIC_ZONE).minusDays(90))
                    ||day.isAfter(LocalDate.now(CLINIC_ZONE).plusDays(1)))
                throw new IllegalArgumentException();
            AuthPrincipal actor=access.requirePermission("OPD_DAILY_REPORT_READ");
            BillingDaily report=transactions.execute(tx->{
                requireBillingActor(actor,branchId,"OPD_DAILY_REPORT_READ");
                var counts=jdbc.query("""
                    SELECT COUNT(*) AS count,
                      COALESCE(SUM(i.total_paise),0) AS billed,
                      COALESCE(SUM(i.total_paise-
                        COALESCE((SELECT SUM(e.amount_paise)
                          FROM appointment_proof.opd_cash_event e
                          WHERE e.invoice_id=i.id AND e.tenant_id=i.tenant_id
                            AND e.branch_id=i.branch_id),0)),0) AS outstanding
                      FROM appointment_proof.opd_invoice i
                    WHERE i.tenant_id=? AND i.branch_id=?
                      AND (i.created_at AT TIME ZONE 'Asia/Kolkata')::date=?
                    """,(rs,i)->new long[]{rs.getLong("count"),
                        rs.getLong("billed"),rs.getLong("outstanding")},
                    actor.tenantId(),branchId,day);
                long[] amounts=counts.getFirst();
                Long collected=jdbc.queryForObject("""
                    SELECT COALESCE(SUM(amount_paise),0)
                      FROM appointment_proof.opd_cash_event
                    WHERE tenant_id=? AND branch_id=?
                      AND (recorded_at AT TIME ZONE 'Asia/Kolkata')::date=?
                    """,Long.class,actor.tenantId(),branchId,day);
                jdbc.update("""
                    INSERT INTO appointment_proof.opd_billing_read_audit
                      (tenant_id,branch_id,actor_membership_id,report_day)
                    VALUES(?,?,?,?)
                    """,actor.tenantId(),branchId,actor.membershipId(),day);
                return new BillingDaily(day,actor.tenantId(),branchId,
                        amounts[0],amounts[1],
                        collected==null?0:collected,amounts[2]);
            });
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(report);
        }

        @ExceptionHandler({IllegalArgumentException.class,
                org.springframework.http.converter.HttpMessageNotReadableException.class})
        ResponseEntity<Error> invalid(Exception ignored) {
            return ResponseEntity.badRequest().cacheControl(CacheControl.noStore())
                    .body(new Error("INVALID_APPOINTMENT_REQUEST"));
        }
        @ExceptionHandler(AccessDeniedException.class)
        ResponseEntity<Error> denied(AccessDeniedException ignored) {
            return ResponseEntity.status(403).cacheControl(CacheControl.noStore())
                    .body(new Error("APPOINTMENT_DENIED"));
        }
        @ExceptionHandler(Conflict.class)
        ResponseEntity<Error> conflict(Conflict ignored) {
            return ResponseEntity.status(409).cacheControl(CacheControl.noStore())
                    .body(new Error("APPOINTMENT_CONFLICT"));
        }
    }


    /** Explicitly separated branch daily-report path; test profile only. */
    @RestController
    @Profile("test & appointment-candidate & !dev & !prod")
    @RequestMapping("/api/v1/branches/{branchId}/billing")
    static class DailyBillingController {
        private final CandidateController candidate;
        DailyBillingController(CandidateController candidate) {
            this.candidate=candidate;
        }
        @GetMapping("/daily")
        ResponseEntity<CandidateController.BillingDaily> daily(
                @PathVariable UUID branchId,
                @RequestParam LocalDate date) {
            return candidate.dailyReport(branchId,date);
        }
    }

    @BeforeAll
    void installOnDisposablePostgresOnly() throws Exception {
        assertThat(postgres.isRunning()).isTrue();
        assertThat(postgres.getDatabaseName()).isEqualTo("aronexa_r104_disposable_only");
        Path base=Path.of(System.getProperty("basedir",
                System.getProperty("user.dir"))).toAbsolutePath();
        assertThat(Files.exists(base.resolve("src/test/resources/db/test/appointment-candidate-r104.sql"))).isTrue();
        assertThat(Files.exists(base.resolve("src/test/resources/db/test/appointment-candidate-r105.sql"))).isTrue();
        assertThat(Files.exists(base.resolve("src/test/resources/db/test/appointment-candidate-r106.sql"))).isTrue();
        assertThat(Files.exists(base.resolve("src/test/resources/db/test/appointment-candidate-r107.sql"))).isTrue();
        assertThat(Files.exists(base.resolve("src/test/resources/db/test/appointment-candidate-r108.sql"))).isTrue();
        try(Connection owner=ownerDataSource.getConnection()) {
            owner.setAutoCommit(false);
            try {
                for(String file:new String[]{"db/test/appointment-persistence-proof.sql",
                        "db/test/appointment-candidate-r104.sql",
                        "db/test/appointment-candidate-r105.sql",
                        "db/test/appointment-candidate-r106.sql",
                        "db/test/appointment-candidate-r107.sql",
                        "db/test/appointment-candidate-r108.sql"}) {
                    ScriptUtils.executeSqlScript(owner,
                            new EncodedResource(new ClassPathResource(file)),
                            false,false,ScriptUtils.DEFAULT_COMMENT_PREFIX,
                            ScriptUtils.EOF_STATEMENT_SEPARATOR,
                            ScriptUtils.DEFAULT_BLOCK_COMMENT_START_DELIMITER,
                            ScriptUtils.DEFAULT_BLOCK_COMMENT_END_DELIMITER);
                }
                owner.commit();
            } catch(Exception failure) {
                owner.rollback();
                throw failure;
            }
        }
        admin.update("UPDATE module_registry SET implemented=true,released=true WHERE code='OPD'");
    }

    record Fixture(UUID tenant,UUID branch,UUID otherBranch,UUID receptionist,
                   UUID doctor,UUID registration,String tenantCode,String username,String password) {}
    private Fixture fixture(boolean withAccess) {
        String suffix=UUID.randomUUID().toString().substring(0,8);
        UUID tenant=UUID.randomUUID(),facility=UUID.randomUUID(),
             branch=UUID.randomUUID(),other=UUID.randomUUID(),
             user=UUID.randomUUID(),membership=UUID.randomUUID(),
             doctorUser=UUID.randomUUID(),doctor=UUID.randomUUID(),
             role=UUID.randomUUID(),doctorRole=UUID.randomUUID(),
             billingUser=UUID.randomUUID(),billing=UUID.randomUUID(),
             billingRole=UUID.randomUUID(),
             patient=UUID.randomUUID(),registration=UUID.randomUUID();
        String username="r104_"+suffix,tenantCode="r104_"+suffix;
        String password="DisposableR104-Only123!";
        admin.update("INSERT INTO tenant(id,code,display_name) VALUES (?,?,?)",
                tenant,tenantCode,"Fictional appointment clinic");
        admin.update("INSERT INTO facility(id,tenant_id,code,display_name,facility_kind) "
                    +"VALUES (?,?,?,'Fictional clinic','CLINIC')",
                facility,tenant,"f_"+suffix);
        admin.update("INSERT INTO branch(id,tenant_id,facility_id,code,display_name) "
                    +"VALUES(?,?,?,?,'Reception branch')",branch,tenant,facility,"b_"+suffix);
        admin.update("INSERT INTO branch(id,tenant_id,facility_id,code,display_name) "
                    +"VALUES(?,?,?,?,'Another branch')",other,tenant,facility,"o_"+suffix);
        admin.update("INSERT INTO app_user(id,username,password_hash,must_change_password) "
                    +"VALUES(?,?,?,false)",user,username,passwords.encode(password));
        admin.update("INSERT INTO user_tenant_membership(id,user_id,tenant_id) VALUES(?,?,?)",
                membership,user,tenant);
        admin.update("INSERT INTO auth_role(id,tenant_id,code,display_name) "
                    +"VALUES (?,?,'RECEPTION','Synthetic receptionist')",role,tenant);
        if(withAccess)for(String permission:new String[]{"APPOINTMENT_BOOK",
                "APPOINTMENT_READ","APPOINTMENT_CHECK_IN",
                "APPOINTMENT_RESCHEDULE","APPOINTMENT_CANCEL"}) {
            admin.update("INSERT INTO auth_role_permission(tenant_id,role_id,permission_code) "
                        +"VALUES(?,?,?)",tenant,role,permission);
        }
        admin.update("INSERT INTO auth_membership_role(tenant_id,membership_id,role_id) "
                    +"VALUES(?,?,?)",tenant,membership,role);
        admin.update("INSERT INTO org_membership_branch_scope(tenant_id,membership_id,branch_id) "
                    +"VALUES (?,?,?)",tenant,membership,branch);
        admin.update("INSERT INTO app_user(id,username,password_hash,must_change_password) "
                    +"VALUES(?,?,?,false)",doctorUser,"doctor_"+suffix,
                    passwords.encode("DisposableDoctor-Only123!"));
        admin.update("INSERT INTO user_tenant_membership(id,user_id,tenant_id) VALUES(?,?,?)",
                    doctor,doctorUser,tenant);
        admin.update("INSERT INTO auth_role(id,tenant_id,code,display_name) "
                    +"VALUES(?,?,'DOCTOR','Synthetic doctor')",doctorRole,tenant);
        admin.update("INSERT INTO auth_membership_role(tenant_id,membership_id,role_id) "
                    +"VALUES(?,?,?)",tenant,doctor,doctorRole);
        admin.update("INSERT INTO org_membership_branch_scope(tenant_id,membership_id,branch_id) "
                    +"VALUES(?,?,?)",tenant,doctor,branch);
        admin.update("INSERT INTO auth_role_permission(tenant_id,role_id,permission_code) "
                    +"VALUES(?,?,'OPD_DRAFT_WRITE')",tenant,doctorRole);
        admin.update("INSERT INTO auth_role_permission(tenant_id,role_id,permission_code) "
                    +"VALUES(?,?,'OPD_RX_DRAFT_WRITE')",tenant,doctorRole);
        admin.update("INSERT INTO app_user(id,username,password_hash,must_change_password) "
                    +"VALUES(?,?,?,false)",billingUser,"billing_"+suffix,
                    passwords.encode("DisposableBilling-Only123!"));
        admin.update("INSERT INTO user_tenant_membership(id,user_id,tenant_id) VALUES(?,?,?)",
                    billing,billingUser,tenant);
        admin.update("INSERT INTO auth_role(id,tenant_id,code,display_name) "
                    +"VALUES(?,?,'BILLING','Synthetic cash recorder')",billingRole,tenant);
        admin.update("INSERT INTO auth_membership_role(tenant_id,membership_id,role_id) "
                    +"VALUES(?,?,?)",tenant,billing,billingRole);
        admin.update("INSERT INTO org_membership_branch_scope(tenant_id,membership_id,branch_id) "
                    +"VALUES(?,?,?)",tenant,billing,branch);
        for(String permission:new String[]{"OPD_BILL_CREATE","OPD_BILL_READ",
                "OPD_PAYMENT_POST","OPD_DAILY_REPORT_READ"}) {
            admin.update("INSERT INTO auth_role_permission(tenant_id,role_id,permission_code) "
                        +"VALUES(?,?,?)",tenant,billingRole,permission);
        }
        admin.update("INSERT INTO appointment_proof.fee_schedule "
                    +"(tenant_id,branch_id,service_code,service_label,amount_paise,enabled) "
                    +"VALUES(?,?,'OPD-CONSULTATION','Fictional OPD Consultation',50000,true)",
                    tenant,branch);
        admin.update("INSERT INTO tenant_module_state(tenant_id,module_code,entitled,activated,clinically_authorized) "
                    +"VALUES(?,'OPD',true,true,true)",tenant);
        admin.update("INSERT INTO patient(id,tenant_id,display_name) "
                    +"VALUES(?,?,'Fictional appointment subject')",patient,tenant);
        admin.update("INSERT INTO patient_identifier(id,tenant_id,patient_id,identifier_type,identifier_value) "
                    +"VALUES (?,?,?,'MRN',?)",UUID.randomUUID(),tenant,patient,"PAT-"+suffix.toUpperCase()+"001");
        admin.update("INSERT INTO patient_registration(id,tenant_id,patient_id,branch_id,registration_source,"
                    +"registered_by_membership_id) VALUES (?,?,?,?,'RECEPTION',?)",
                    registration,tenant,patient,branch,membership);
        return new Fixture(tenant,branch,other,membership,doctor,registration,
                tenantCode,username,password);
    }

    private HttpResponse<String> call(String method,String path,String token,String body)
            throws Exception {
        var req=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path))
                .header("Accept","application/json");
        if(token!=null)req.header("Authorization","Bearer "+token);
        if(method.equals("POST"))req.header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        else req.GET();
        return http.send(req.build(),HttpResponse.BodyHandlers.ofString());
    }
    private String login(Fixture f) throws Exception {
        String body="{\"tenantCode\":\""+f.tenantCode()+"\",\"username\":\""+
                f.username()+"\",\"password\":\""+f.password()+"\"}";
        var response=call("POST","/api/v1/auth/login",null,body);
        assertThat(response.statusCode()).isEqualTo(200);
        var token=Pattern.compile("\"accessToken\"\\s*:\\s*\"([A-Za-z0-9_-]{43})\"")
                .matcher(response.body());
        assertThat(token.find()).isTrue();
        return token.group(1);
    }
    private String url(Fixture f) {
        return "/api/v1/branches/"+f.branch()+"/appointments";
    }
    private String payload(Fixture f, UUID requestId, UUID doctor, UUID registration,
                           LocalDate day,String slot) {
        return "{\"requestId\":\""+requestId+"\",\"registrationId\":\""+registration+
                "\",\"doctorMembershipId\":\""+doctor+"\",\"visitDate\":\""+day+
                "\",\"slotStart\":\""+slot+"\"}";
    }
    private static UUID id(String json,String key) {
        Matcher m=Pattern.compile("\""+key+"\"\\s*:\\s*\"([a-f0-9-]{36})\"")
                .matcher(json);
        assertThat(m.find()).as(key+" should be included in the response").isTrue();
        return UUID.fromString(m.group(1));
    }
    private int count(String relation, UUID tenant) {
        return admin.queryForObject("SELECT count(*) FROM appointment_proof."+relation+
                " WHERE tenant_id=?",Integer.class,tenant);
    }

    @Test
    void realHttpBookingPersistsThroughFreshLoginThenCheckInWithAudit() throws Exception {
        Fixture f=fixture(true);
        String token=login(f);
        LocalDate today=LocalDate.now(CLINIC_ZONE);
        UUID requestId=UUID.randomUUID();
        String body=payload(f,requestId,f.doctor(),f.registration(),today,"09:00");
        var created=call("POST",url(f),token,body);
        assertThat(created.statusCode()).isEqualTo(201);
        assertThat(created.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(created.body()).contains("\"stage\":\"BOOKED\"","\"revision\":1");
        UUID appointment=id(created.body(),"appointmentId");
        var retry=call("POST",url(f),token,body);
        assertThat(retry.statusCode()).isEqualTo(200);
        assertThat(retry.body()).contains("\"replayed\":true");
        assertThat(id(retry.body(),"appointmentId")).isEqualTo(appointment);
        assertThat(count("appointment",f.tenant())).isOne();
        assertThat(count("event",f.tenant())).isOne();

        // Actual session revocation followed by fresh independent HTTP login.
        assertThat(call("POST","/api/v1/auth/logout",token,"{}").statusCode())
                .isBetween(200,204);
        assertThat(call("GET",url(f)+"/"+appointment,token,null).statusCode())
                .isEqualTo(401);
        String nextToken=login(f);
        assertThat(nextToken).isNotEqualTo(token);
        var found=call("GET",url(f)+"/"+appointment,nextToken,null);
        assertThat(found.statusCode()).isEqualTo(200);
        assertThat(found.body()).contains("\"stage\":\"BOOKED\"","\"revision\":1");
        assertThat(id(found.body(),"registrationId")).isEqualTo(f.registration());
        assertThat(count("read_audit",f.tenant())).isOne();

        var checked=call("POST",url(f)+"/"+appointment+"/check-in",
                nextToken,"{\"expectedRevision\":1}");
        assertThat(checked.statusCode()).isEqualTo(200);
        assertThat(checked.body()).contains("\"stage\":\"CHECKED_IN\"","\"revision\":2");
        assertThat(count("event",f.tenant())).isEqualTo(2);
        assertThat(admin.queryForList("SELECT event_code FROM appointment_proof.event "
                +"WHERE appointment_id=? ORDER BY revision",
                String.class,appointment)).containsExactly("BOOKED","CHECKED_IN");
        assertThat(call("POST",url(f)+"/"+appointment+"/check-in",
                nextToken,"{\"expectedRevision\":1}").statusCode()).isEqualTo(409);
        assertThat(count("event",f.tenant())).isEqualTo(2);
    }

    @Test
    void slotCollisionAndIdempotencyConflictNeverCreateSecondBooking() throws Exception {
        Fixture f=fixture(true);
        String token=login(f);
        LocalDate day=LocalDate.now(CLINIC_ZONE);
        String first=payload(f,UUID.randomUUID(),f.doctor(),f.registration(),day,"09:15");
        assertThat(call("POST",url(f),token,first).statusCode()).isEqualTo(201);
        var another=call("POST",url(f),token,
                payload(f,UUID.randomUUID(),f.doctor(),f.registration(),day,"09:15"));
        assertThat(another.statusCode()).isEqualTo(409);
        assertThat(count("appointment",f.tenant())).isOne();
        assertThat(count("event",f.tenant())).isOne();
        UUID reused=UUID.randomUUID();
        var booked=call("POST",url(f),token,
                payload(f,reused,f.doctor(),f.registration(),day,"09:30"));
        assertThat(booked.statusCode()).isEqualTo(201);
        var conflict=call("POST",url(f),token,
                payload(f,reused,f.doctor(),f.registration(),day,"09:45"));
        assertThat(conflict.statusCode()).isEqualTo(409);
        assertThat(count("appointment",f.tenant())).isEqualTo(2);
    }

    @Test
    void wrongRegistrationDoctorBranchAndUnauthorizedRoleFailClosed() throws Exception {
        Fixture f=fixture(true),other=fixture(true);
        String token=login(f);
        LocalDate day=LocalDate.now(CLINIC_ZONE);
        var crossTenant=call("POST",url(f),token,payload(f,UUID.randomUUID(),
                f.doctor(),other.registration(),day,"10:00"));
        assertThat(crossTenant.statusCode()).isEqualTo(403);
        var foreignDoctor=call("POST",url(f),token,payload(f,UUID.randomUUID(),
                other.doctor(),f.registration(),day,"10:00"));
        assertThat(foreignDoctor.statusCode()).isEqualTo(403);
        var foreignBranch=call("POST",
                "/api/v1/branches/"+f.otherBranch()+"/appointments",token,
                payload(f,UUID.randomUUID(),f.doctor(),f.registration(),day,"10:00"));
        assertThat(foreignBranch.statusCode()).isEqualTo(403);
        Fixture denied=fixture(false);
        var noPermission=call("POST",url(denied),login(denied),
                payload(denied,UUID.randomUUID(),denied.doctor(),
                    denied.registration(),day,"10:00"));
        assertThat(noPermission.statusCode()).isEqualTo(403);
        assertThat(count("appointment",f.tenant())).isZero();
        assertThat(count("appointment",denied.tenant())).isZero();
    }

    @Test
    void dateAndSlotValidationRejectUnusableBooking() throws Exception {
        Fixture f=fixture(true);
        String token=login(f);
        LocalDate day=LocalDate.now(CLINIC_ZONE);
        for(String bad:new String[]{
                payload(f,UUID.randomUUID(),f.doctor(),f.registration(),day,"08:30"),
                payload(f,UUID.randomUUID(),f.doctor(),f.registration(),day,"09:07"),
                payload(f,UUID.randomUUID(),f.doctor(),f.registration(),
                        day.minusDays(1),"09:00"),
                payload(f,UUID.randomUUID(),f.doctor(),f.registration(),
                        day.plusDays(91),"09:00")}) {
            assertThat(call("POST",url(f),token,bad).statusCode()).isEqualTo(400);
        }
        assertThat(count("appointment",f.tenant())).isZero();
    }

    private String modifyPayload(UUID doctor, LocalDate day,String slot,int revision) {
        return "{\"doctorMembershipId\":\""+doctor+"\",\"visitDate\":\""+day+
                "\",\"slotStart\":\""+slot+"\",\"expectedRevision\":"+revision+"}";
    }

    @Test
    void rescheduleMovesDurableSlotAndDailyQueueReadsOnlyCurrentActiveRows() throws Exception {
        Fixture f=fixture(true);
        String token=login(f);
        LocalDate today=LocalDate.now(CLINIC_ZONE);
        var booked=call("POST",url(f),token,payload(f,UUID.randomUUID(),
                f.doctor(),f.registration(),today,"10:00"));
        assertThat(booked.statusCode()).isEqualTo(201);
        UUID id=id(booked.body(),"appointmentId");
        var moved=call("POST",url(f)+"/"+id+"/reschedule",token,
                modifyPayload(f.doctor(),today,"10:15",1));
        assertThat(moved.statusCode()).isEqualTo(200);
        assertThat(moved.body()).contains("\"stage\":\"BOOKED\"","\"revision\":2",
                "\"slotStart\":\"10:15:00\"");
        assertThat(admin.queryForList("SELECT event_code FROM appointment_proof.event "
                +"WHERE appointment_id=? ORDER BY revision",String.class,id))
                .containsExactly("BOOKED","RESCHEDULED");

        // The original slot is free, while the new one remains occupied.
        assertThat(call("POST",url(f),token,payload(f,UUID.randomUUID(),
                f.doctor(),f.registration(),today,"10:00")).statusCode())
                .isEqualTo(201);
        assertThat(call("POST",url(f),token,payload(f,UUID.randomUUID(),
                f.doctor(),f.registration(),today,"10:15")).statusCode())
                .isEqualTo(409);

        var queue=call("GET",url(f)+"?visitDate="+today,token,null);
        assertThat(queue.statusCode()).isEqualTo(200);
        assertThat(queue.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(queue.body()).contains(id.toString(),"\"stage\":\"BOOKED\"");
        assertThat(count("appointment",f.tenant())).isEqualTo(2);
        assertThat(count("read_audit",f.tenant())).isEqualTo(2);

        // A different local date does not return today's visits.
        var nextDay=call("GET",url(f)+"?visitDate="+today.plusDays(1),token,null);
        assertThat(nextDay.statusCode()).isEqualTo(200);
        assertThat(nextDay.body()).isEqualTo("[]");
    }

    @Test
    void cancellationReleasesSlotButImmutableAuditAndIdLookupRemain() throws Exception {
        Fixture f=fixture(true);
        String token=login(f);
        LocalDate today=LocalDate.now(CLINIC_ZONE);
        var booked=call("POST",url(f),token,payload(f,UUID.randomUUID(),
                f.doctor(),f.registration(),today,"10:30"));
        assertThat(booked.statusCode()).isEqualTo(201);
        UUID appointment=id(booked.body(),"appointmentId");
        var cancelled=call("POST",url(f)+"/"+appointment+"/cancel",token,
                "{\"expectedRevision\":1}");
        assertThat(cancelled.statusCode()).isEqualTo(200);
        assertThat(cancelled.body()).contains("\"stage\":\"CANCELLED\"","\"revision\":2");
        assertThat(call("POST",url(f)+"/"+appointment+"/cancel",token,
                "{\"expectedRevision\":1}").statusCode()).isEqualTo(409);
        assertThat(call("POST",url(f)+"/"+appointment+"/check-in",token,
                "{\"expectedRevision\":1}").statusCode()).isEqualTo(409);
        assertThat(admin.queryForList("SELECT event_code FROM appointment_proof.event "
                +"WHERE appointment_id=? ORDER BY revision",String.class,appointment))
                .containsExactly("BOOKED","CANCELLED");
        assertThat(call("GET",url(f)+"?visitDate="+today,token,null).body())
                .isEqualTo("[]");
        var history=call("GET",url(f)+"/"+appointment,token,null);
        assertThat(history.statusCode()).isEqualTo(200);
        assertThat(history.body()).contains("\"stage\":\"CANCELLED\"");
        assertThat(call("POST",url(f),token,payload(f,UUID.randomUUID(),
                f.doctor(),f.registration(),today,"10:30")).statusCode())
                .isEqualTo(201);
        assertThat(count("event",f.tenant())).isEqualTo(3);
    }

    @Test
    void collisionNoopStaleRevisionAndCheckedInTransitionNeverOverwrite() throws Exception {
        Fixture f=fixture(true);
        String token=login(f);
        LocalDate today=LocalDate.now(CLINIC_ZONE);
        var first=call("POST",url(f),token,payload(f,UUID.randomUUID(),
                f.doctor(),f.registration(),today,"11:00"));
        var second=call("POST",url(f),token,payload(f,UUID.randomUUID(),
                f.doctor(),f.registration(),today,"11:15"));
        assertThat(first.statusCode()).isEqualTo(201);
        assertThat(second.statusCode()).isEqualTo(201);
        UUID appointment=id(first.body(),"appointmentId");
        assertThat(call("POST",url(f)+"/"+appointment+"/reschedule",token,
                modifyPayload(f.doctor(),today,"11:15",1)).statusCode())
                .isEqualTo(409);
        assertThat(call("POST",url(f)+"/"+appointment+"/reschedule",token,
                modifyPayload(f.doctor(),today,"11:00",1)).statusCode())
                .isEqualTo(409);
        assertThat(count("event",f.tenant())).isEqualTo(2);

        assertThat(call("POST",url(f)+"/"+appointment+"/reschedule",token,
                modifyPayload(f.doctor(),today,"11:30",1)).statusCode())
                .isEqualTo(200);
        assertThat(call("POST",url(f)+"/"+appointment+"/reschedule",token,
                modifyPayload(f.doctor(),today,"11:45",1)).statusCode())
                .isEqualTo(409);
        assertThat(call("POST",url(f)+"/"+appointment+"/cancel",token,
                "{\"expectedRevision\":1}").statusCode()).isEqualTo(409);
        assertThat(call("POST",url(f)+"/"+appointment+"/check-in",token,
                "{\"expectedRevision\":2}").statusCode()).isEqualTo(200);
        assertThat(call("POST",url(f)+"/"+appointment+"/reschedule",token,
                modifyPayload(f.doctor(),today,"11:45",3)).statusCode())
                .isEqualTo(409);
        assertThat(call("POST",url(f)+"/"+appointment+"/cancel",token,
                "{\"expectedRevision\":3}").statusCode()).isEqualTo(409);
        assertThat(admin.queryForList("SELECT event_code FROM appointment_proof.event "
                +"WHERE appointment_id=? ORDER BY revision",String.class,appointment))
                .containsExactly("BOOKED","RESCHEDULED","CHECKED_IN");
    }

    @Test
    void queueAndMutationsFailClosedOnWrongBranchTenantRevokedPermissionAndModule()
            throws Exception {
        Fixture f=fixture(true),foreign=fixture(true),denied=fixture(false);
        String token=login(f);
        LocalDate today=LocalDate.now(CLINIC_ZONE);
        var booked=call("POST",url(f),token,payload(f,UUID.randomUUID(),
                f.doctor(),f.registration(),today,"12:00"));
        assertThat(booked.statusCode()).isEqualTo(201);
        UUID appointment=id(booked.body(),"appointmentId");
        assertThat(call("GET","/api/v1/branches/"+f.otherBranch()
                +"/appointments?visitDate="+today,token,null).statusCode())
                .isEqualTo(403);
        assertThat(call("GET",url(f)+"?visitDate="+today,login(foreign),null)
                .statusCode()).isEqualTo(403);
        assertThat(call("GET",url(denied)+"?visitDate="+today,login(denied),null)
                .statusCode()).isEqualTo(403);
        assertThat(call("POST",url(f)+"/"+appointment+"/cancel",login(foreign),
                "{\"expectedRevision\":1}").statusCode()).isEqualTo(403);

        // Live role revocation must be enforced after initial session login.
        admin.update("DELETE FROM auth_role_permission WHERE tenant_id=? "
                +"AND permission_code='APPOINTMENT_RESCHEDULE'",f.tenant());
        assertThat(call("POST",url(f)+"/"+appointment+"/reschedule",token,
                modifyPayload(f.doctor(),today,"12:15",1)).statusCode())
                .isEqualTo(403);
        admin.update("DELETE FROM auth_role_permission WHERE tenant_id=? "
                +"AND permission_code='APPOINTMENT_CANCEL'",f.tenant());
        assertThat(call("POST",url(f)+"/"+appointment+"/cancel",token,
                "{\"expectedRevision\":1}").statusCode()).isEqualTo(403);
        admin.update("UPDATE tenant_module_state SET activated=false "
                +"WHERE tenant_id=? AND module_code='OPD'",f.tenant());
        assertThat(call("GET",url(f)+"?visitDate="+today,token,null).statusCode())
                .isEqualTo(403);
        assertThat(count("event",f.tenant())).isOne();
    }


    private String loginDoctor(Fixture f) throws Exception {
        String suffix=f.username().substring("r104_".length());
        String body="{\"tenantCode\":\""+f.tenantCode()
                +"\",\"username\":\"doctor_"+suffix
                +"\",\"password\":\"DisposableDoctor-Only123!\"}";
        var response=call("POST","/api/v1/auth/login",null,body);
        assertThat(response.statusCode()).isEqualTo(200);
        var match=Pattern.compile("\"accessToken\"\\s*:\\s*\"([A-Za-z0-9_-]{43})\"")
                .matcher(response.body());
        assertThat(match.find()).isTrue();
        return match.group(1);
    }

    private String consultationPath(Fixture f,UUID appointment) {
        return url(f)+"/"+appointment+"/consultation-draft";
    }

    private static String consultationInput(int revision,String note) {
        // Fictional values ONLY in tests. No real name/clinical note in logs.
        return "{\"expectedRevision\":"+revision
                +",\"consultationNote\":\""+note+"\"}";
    }

    private UUID checkedInAppointment(Fixture f,String reception,String slot)
            throws Exception {
        LocalDate day=LocalDate.now(CLINIC_ZONE);
        var booked=call("POST",url(f),reception,payload(f,UUID.randomUUID(),
                f.doctor(),f.registration(),day,slot));
        assertThat(booked.statusCode()).isEqualTo(201);
        UUID id=id(booked.body(),"appointmentId");
        assertThat(call("POST",url(f)+"/"+id+"/check-in",reception,
                "{\"expectedRevision\":1}").statusCode()).isEqualTo(200);
        return id;
    }

    @Test
    void assignedDoctorSavesDurableConsultationRevisionsAcrossIndependentLogin()
            throws Exception {
        Fixture f=fixture(true);
        String reception=login(f),doctor=loginDoctor(f);
        UUID appointment=checkedInAppointment(f,reception,"13:00");
        String endpoint=consultationPath(f,appointment);

        var identity=call("GET",url(f)+"/"+appointment+"/consultation-context",
                doctor,null);
        assertThat(identity.statusCode()).isEqualTo(200);
        assertThat(identity.body()).contains(
                "\"appointmentStage\":\"CHECKED_IN\"",
                "\"patientId\":\"");
        assertThat(call("GET",url(f)+"/"+appointment+"/consultation-context",
                reception,null).statusCode()).isEqualTo(403);
        assertThat(call("GET",endpoint,reception,null).statusCode()).isEqualTo(403);
        var initial=call("GET",endpoint,doctor,null);
        assertThat(initial.statusCode()).isEqualTo(200);
        assertThat(initial.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(initial.body()).contains("\"stage\":\"CHECKED_IN\"",
                "\"revision\":0","\"consultationNote\":\"\"");

        var first=call("POST",endpoint,doctor,
                consultationInput(0,"SYNTHETIC OPD note version one"));
        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(first.body()).contains("\"stage\":\"CONSULTATION_DRAFT\"",
                "\"revision\":1","SYNTHETIC OPD note version one");

        assertThat(call("POST","/api/v1/auth/logout",doctor,"{}").statusCode())
                .isBetween(200,204);
        assertThat(call("GET",endpoint,doctor,null).statusCode()).isEqualTo(401);

        String nextDoctor=loginDoctor(f);
        var recovered=call("GET",endpoint,nextDoctor,null);
        assertThat(recovered.statusCode()).isEqualTo(200);
        assertThat(recovered.body()).contains("\"revision\":1",
                "SYNTHETIC OPD note version one");
        var updated=call("POST",endpoint,nextDoctor,
                consultationInput(1,"SYNTHETIC OPD note version two"));
        assertThat(updated.statusCode()).isEqualTo(200);
        assertThat(updated.body()).contains("\"revision\":2",
                "SYNTHETIC OPD note version two");

        assertThat(admin.queryForList("""
            SELECT consultation_note FROM appointment_proof.consultation_revision
            WHERE appointment_id=? ORDER BY revision
            """,String.class,appointment))
            .containsExactly("SYNTHETIC OPD note version one",
                             "SYNTHETIC OPD note version two");
        assertThat(admin.queryForObject("""
            SELECT count(*) FROM appointment_proof.consultation_read_audit
             WHERE appointment_id=?
            """,Integer.class,appointment)).isEqualTo(3);
        assertThat(admin.queryForObject("""
            SELECT count(*) FROM appointment_proof.consultation_revision
             WHERE appointment_id=?
            """,Integer.class,appointment)).isEqualTo(2);
    }

    @Test
    void consultationDraftStaleRevisionAndInvalidNotesFailWithoutHistoryChange()
            throws Exception {
        Fixture f=fixture(true);
        String reception=login(f),doctor=loginDoctor(f);
        UUID appointment=checkedInAppointment(f,reception,"13:15");
        String endpoint=consultationPath(f,appointment);
        assertThat(call("POST",endpoint,doctor,
                consultationInput(0,"SYNTHETIC original note")).statusCode())
                .isEqualTo(200);
        assertThat(call("POST",endpoint,doctor,
                consultationInput(0,"SYNTHETIC stale overwrite")).statusCode())
                .isEqualTo(409);
        assertThat(call("POST",endpoint,doctor,
                consultationInput(1,"    ")).statusCode()).isEqualTo(400);
        assertThat(call("POST",endpoint,doctor,
                consultationInput(1,"x".repeat(2001))).statusCode()).isEqualTo(400);
        assertThat(admin.queryForList("""
            SELECT consultation_note FROM appointment_proof.consultation_revision
            WHERE appointment_id=? ORDER BY revision
            """,String.class,appointment)).containsExactly("SYNTHETIC original note");
        assertThat(call("GET",endpoint,doctor,null).body())
                .contains("\"revision\":1","SYNTHETIC original note");

        // Revision rows are append-only, including old note versions.
        org.assertj.core.api.Assertions.assertThatThrownBy(()->admin.update("""
            UPDATE appointment_proof.consultation_revision
               SET consultation_note='WRONG' WHERE appointment_id=?
            """,appointment)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(()->admin.update("""
            DELETE FROM appointment_proof.consultation_revision WHERE appointment_id=?
            """,appointment)).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    @Test
    void doctorCannotOpenBookedCancelledForeignOrWrongBranchConsultation()
            throws Exception {
        Fixture f=fixture(true),foreign=fixture(true);
        String reception=login(f),doctor=loginDoctor(f),otherDoctor=loginDoctor(foreign);
        LocalDate today=LocalDate.now(CLINIC_ZONE);
        var booked=call("POST",url(f),reception,payload(f,UUID.randomUUID(),
                f.doctor(),f.registration(),today,"13:30"));
        assertThat(booked.statusCode()).isEqualTo(201);
        UUID appointment=id(booked.body(),"appointmentId");
        String endpoint=consultationPath(f,appointment);

        assertThat(call("GET",endpoint,doctor,null).statusCode()).isEqualTo(409);
        assertThat(call("POST",endpoint,doctor,
                consultationInput(0,"SYNTHETIC premature")).statusCode()).isEqualTo(409);
        assertThat(call("POST",url(f)+"/"+appointment+"/cancel",reception,
                "{\"expectedRevision\":1}").statusCode()).isEqualTo(200);
        assertThat(call("GET",endpoint,doctor,null).statusCode()).isEqualTo(409);
        assertThat(call("GET",endpoint,otherDoctor,null).statusCode()).isEqualTo(403);
        assertThat(call("GET",
                "/api/v1/branches/"+f.otherBranch()+"/appointments/"
                +appointment+"/consultation-draft",doctor,null).statusCode())
                .isEqualTo(403);
        assertThat(admin.queryForObject("""
            SELECT count(*) FROM appointment_proof.consultation_revision
            WHERE appointment_id=?
            """,Integer.class,appointment)).isZero();
    }

    @Test
    void consultationRespectsLiveDoctorPermissionAndOpdModuleRevocation()
            throws Exception {
        Fixture f=fixture(true);
        String reception=login(f),doctor=loginDoctor(f);
        UUID appointment=checkedInAppointment(f,reception,"13:45");
        String endpoint=consultationPath(f,appointment);
        assertThat(call("POST",endpoint,doctor,
                consultationInput(0,"SYNTHETIC saved before revocation"))
                .statusCode()).isEqualTo(200);

        admin.update("""
            DELETE FROM auth_role_permission
             WHERE tenant_id=? AND permission_code='OPD_DRAFT_WRITE'
            """,f.tenant());
        assertThat(call("GET",endpoint,doctor,null).statusCode()).isEqualTo(403);
        assertThat(call("POST",endpoint,doctor,
                consultationInput(1,"SYNTHETIC denied")).statusCode()).isEqualTo(403);

        admin.update("""
            INSERT INTO auth_role_permission (tenant_id,role_id,permission_code)
            SELECT ?,id,'OPD_DRAFT_WRITE' FROM auth_role
            WHERE tenant_id=? AND code='DOCTOR'
            """,f.tenant(),f.tenant());
        admin.update("UPDATE tenant_module_state SET activated=false "
                +"WHERE tenant_id=? AND module_code='OPD'",f.tenant());
        assertThat(call("GET",endpoint,doctor,null).statusCode()).isEqualTo(403);
        assertThat(admin.queryForObject("""
            SELECT count(*) FROM appointment_proof.consultation_revision
             WHERE appointment_id=?
            """,Integer.class,appointment)).isOne();
    }


    private String rxPath(Fixture f,UUID appointment) {
        return url(f)+"/"+appointment+"/prescription-draft";
    }

    private String rxMedicineJson(UUID id,String medicine) {
        return "{\"entryId\":\""+id+"\",\"medicine\":\""+medicine+
                "\",\"strength\":\"FICTIONAL 1\",\"form\":\"tablet\","+
                "\"dose\":\"FICTIONAL doctor-entered\",\"route\":\"oral\","+
                "\"frequency\":\"FICTIONAL schedule\","+
                "\"duration\":\"FICTIONAL duration\",\"quantity\":1,"+
                "\"instructions\":\"NOT A REAL PRESCRIPTION\"}";
    }

    private String rxInput(int revision,int consultationRev,String medicinesJson) {
        return "{\"expectedRevision\":"+revision+
                ",\"consultationRevision\":"+consultationRev+
                ",\"medicines\":"+medicinesJson+"}";
    }

    private UUID readyForRx(Fixture f,String receptionist,String doctor,String slot)
            throws Exception {
        UUID appointment=checkedInAppointment(f,receptionist,slot);
        assertThat(call("POST",consultationPath(f,appointment),doctor,
                consultationInput(0,"SYNTHETIC consultation draft for Rx"))
                .statusCode()).isEqualTo(200);
        return appointment;
    }

    @Test
    void prescriptionDraftPersistsImmutableMedicineSnapshotsAcrossFreshDoctorLogin()
            throws Exception {
        Fixture f=fixture(true);
        String receptionist=login(f),doctor=loginDoctor(f);
        UUID appt=readyForRx(f,receptionist,doctor,"14:00");
        String endpoint=rxPath(f,appt);
        UUID entry=UUID.randomUUID();
        var empty=call("GET",endpoint,doctor,null);
        assertThat(empty.statusCode()).isEqualTo(200);
        assertThat(empty.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(empty.body()).contains("\"stage\":\"EMPTY_DRAFT\"",
                "\"revision\":0","\"consultationRevision\":1","\"medicines\":[]");
        String first=rxInput(0,1,"["+rxMedicineJson(entry,"FICTIONAL medicine Alpha")+"]");
        var created=call("POST",endpoint,doctor,first);
        assertThat(created.statusCode()).isEqualTo(200);
        assertThat(created.body()).contains("\"stage\":\"RX_DRAFT\"",
                "\"revision\":1","FICTIONAL medicine Alpha");
        assertThat(call("POST","/api/v1/auth/logout",doctor,"{}")
                .statusCode()).isBetween(200,204);
        assertThat(call("GET",endpoint,doctor,null).statusCode()).isEqualTo(401);

        String newToken=loginDoctor(f);
        var reopened=call("GET",endpoint,newToken,null);
        assertThat(reopened.statusCode()).isEqualTo(200);
        assertThat(reopened.body()).contains("\"revision\":1",
                "\"consultationRevision\":1","FICTIONAL medicine Alpha");
        UUID secondEntry=UUID.randomUUID();
        var revised=call("POST",endpoint,newToken,
                rxInput(1,1,"["+rxMedicineJson(entry,"FICTIONAL medicine Beta")
                    +","+rxMedicineJson(secondEntry,"FICTIONAL medicine Gamma")+"]"));
        assertThat(revised.statusCode()).isEqualTo(200);
        assertThat(revised.body()).contains("\"revision\":2",
                "FICTIONAL medicine Beta","FICTIONAL medicine Gamma");

        assertThat(admin.queryForList("""
            SELECT m.medicine FROM appointment_proof.rx_draft_medicine m
             WHERE appointment_id=? ORDER BY revision,line_order
            """,String.class,appt)).containsExactly(
                "FICTIONAL medicine Alpha","FICTIONAL medicine Beta",
                "FICTIONAL medicine Gamma");
        assertThat(admin.queryForObject("""
            SELECT count(*) FROM appointment_proof.rx_draft_revision
             WHERE appointment_id=?
            """,Integer.class,appt)).isEqualTo(2);
        assertThat(admin.queryForObject("""
            SELECT count(*) FROM appointment_proof.rx_draft_read_audit
             WHERE appointment_id=?
            """,Integer.class,appt)).isEqualTo(2);
        // An immutable old medicine line can never be silently updated/deleted.
        org.assertj.core.api.Assertions.assertThatThrownBy(()->admin.update("""
            UPDATE appointment_proof.rx_draft_medicine SET medicine='TAMPER'
             WHERE appointment_id=? AND revision=1
            """,appt)).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    @Test
    void rxRequiresCheckedInVisitAndSavedConsultationAssignedDoctorOnly()
            throws Exception {
        Fixture f=fixture(true),foreign=fixture(true);
        String reception=login(f),doctor=loginDoctor(f);
        LocalDate day=LocalDate.now(CLINIC_ZONE);
        var booked=call("POST",url(f),reception,payload(f,UUID.randomUUID(),
                f.doctor(),f.registration(),day,"14:15"));
        assertThat(booked.statusCode()).isEqualTo(201);
        UUID appt=id(booked.body(),"appointmentId");
        String endpoint=rxPath(f,appt);
        assertThat(call("GET",endpoint,doctor,null).statusCode()).isEqualTo(409);
        assertThat(call("POST",endpoint,doctor,
                rxInput(0,1,"["+rxMedicineJson(UUID.randomUUID(),"FICTIONAL")+"]"))
                .statusCode()).isEqualTo(403);
        assertThat(call("POST",url(f)+"/"+appt+"/check-in",reception,
                "{\"expectedRevision\":1}").statusCode()).isEqualTo(200);
        assertThat(call("GET",endpoint,doctor,null).statusCode()).isEqualTo(409);
        assertThat(call("GET",endpoint,reception,null).statusCode()).isEqualTo(403);
        assertThat(call("GET",endpoint,loginDoctor(foreign),null)
                .statusCode()).isEqualTo(403);
        assertThat(call("GET",
                "/api/v1/branches/"+f.otherBranch()+"/appointments/"
                 +appt+"/prescription-draft",doctor,null).statusCode()).isEqualTo(403);
        assertThat(admin.queryForObject("""
            SELECT count(*) FROM appointment_proof.rx_draft_revision
             WHERE appointment_id=?
            """,Integer.class,appt)).isZero();
    }

    @Test
    void rxRejectedOnStaleRevisionInvalidMedicineOrConsultationChange()
            throws Exception {
        Fixture f=fixture(true);
        String reception=login(f),doctor=loginDoctor(f);
        UUID appt=readyForRx(f,reception,doctor,"14:30");
        String endpoint=rxPath(f,appt);
        UUID line=UUID.randomUUID();
        String valid=rxMedicineJson(line,"FICTIONAL tablet");
        assertThat(call("POST",endpoint,doctor,
                rxInput(0,1,"["+valid+"]")).statusCode()).isEqualTo(200);
        assertThat(call("POST",endpoint,doctor,
                rxInput(0,1,"["+valid+"]")).statusCode()).isEqualTo(409);
        assertThat(call("POST",endpoint,doctor,
                rxInput(1,1,"[]")).statusCode()).isEqualTo(400);
        assertThat(call("POST",endpoint,doctor,
                rxInput(1,1,"["+valid+","+valid+"]")).statusCode()).isEqualTo(400);
        String blank=rxMedicineJson(UUID.randomUUID(),"  ");
        assertThat(call("POST",endpoint,doctor,
                rxInput(1,1,"["+blank+"]")).statusCode()).isEqualTo(400);
        String invalidQuantity=valid.replace("\"quantity\":1","\"quantity\":0");
        assertThat(call("POST",endpoint,doctor,
                rxInput(1,1,"["+invalidQuantity+"]")).statusCode()).isEqualTo(400);
        assertThat(call("POST",endpoint,doctor,
                rxInput(1,2,"["+valid+"]")).statusCode()).isEqualTo(409);
        assertThat(admin.queryForObject("""
            SELECT count(*) FROM appointment_proof.rx_draft_revision
             WHERE appointment_id=?
            """,Integer.class,appt)).isOne();

        // A consultation revision change invalidates an Rx snapshot until
        // an explicit future reconciliation workflow is reviewed.
        assertThat(call("POST",consultationPath(f,appt),doctor,
                consultationInput(1,"SYNTHETIC revised consultation"))
                .statusCode()).isEqualTo(200);
        assertThat(call("GET",endpoint,doctor,null).statusCode()).isEqualTo(409);
        assertThat(call("POST",endpoint,doctor,
                rxInput(1,2,"["+valid+"]")).statusCode()).isEqualTo(409);
        assertThat(admin.queryForObject("""
            SELECT count(*) FROM appointment_proof.rx_draft_medicine
             WHERE appointment_id=?
            """,Integer.class,appt)).isOne();
    }

    @Test
    void rxLivePermissionModuleAndSessionRevocationFailClosed() throws Exception {
        Fixture f=fixture(true);
        String reception=login(f),doctor=loginDoctor(f);
        UUID appt=readyForRx(f,reception,doctor,"14:45");
        String endpoint=rxPath(f,appt);
        UUID line=UUID.randomUUID();
        admin.update("""
            DELETE FROM auth_role_permission WHERE tenant_id=?
              AND permission_code='OPD_RX_DRAFT_WRITE'
            """,f.tenant());
        assertThat(call("GET",endpoint,doctor,null).statusCode()).isEqualTo(403);
        assertThat(call("POST",endpoint,doctor,rxInput(0,1,
                "["+rxMedicineJson(line,"FICTIONAL")+"]")).statusCode())
                .isEqualTo(403);
        admin.update("""
            INSERT INTO auth_role_permission (tenant_id,role_id,permission_code)
            SELECT ?,id,'OPD_RX_DRAFT_WRITE' FROM auth_role
            WHERE tenant_id=? AND code='DOCTOR'
            """,f.tenant(),f.tenant());
        admin.update("""
            UPDATE tenant_module_state SET activated=false
             WHERE tenant_id=? AND module_code='OPD'
            """,f.tenant());
        assertThat(call("GET",endpoint,doctor,null).statusCode()).isEqualTo(403);
        // Restore the synthetic test-only module, then prove logout revocation
        // rejects the previous bearer token even when the doctor role is live.
        admin.update("""
            UPDATE tenant_module_state SET activated=true
             WHERE tenant_id=? AND module_code='OPD'
            """,f.tenant());
        assertThat(call("GET",endpoint,doctor,null).statusCode()).isEqualTo(200);
        assertThat(call("POST","/api/v1/auth/logout",doctor,"{}")
                .statusCode()).isBetween(200,204);
        assertThat(call("GET",endpoint,doctor,null).statusCode()).isEqualTo(401);
        assertThat(admin.queryForObject("""
            SELECT count(*) FROM appointment_proof.rx_draft_revision
             WHERE appointment_id=?
            """,Integer.class,appt)).isZero();
    }



    private String loginBilling(Fixture f) throws Exception {
        String suffix=f.username().substring("r104_".length());
        String body="{\"tenantCode\":\""+f.tenantCode()
                +"\",\"username\":\"billing_"+suffix
                +"\",\"password\":\"DisposableBilling-Only123!\"}";
        var response=call("POST","/api/v1/auth/login",null,body);
        assertThat(response.statusCode()).isEqualTo(200);
        var match=Pattern.compile("\"accessToken\"\\s*:\\s*\"([A-Za-z0-9_-]{43})\"")
                .matcher(response.body());
        assertThat(match.find()).isTrue();
        return match.group(1);
    }

    private String billPath(Fixture f,UUID appointment) {
        return url(f)+"/"+appointment+"/bill";
    }
    private String billCreatePayload(UUID requestId) {
        return "{\"requestId\":\""+requestId
                +"\",\"serviceCode\":\"OPD-CONSULTATION\"}";
    }
    private String cashPayload(UUID invoiceId,UUID requestId,long amount) {
        return "{\"invoiceId\":\""+invoiceId+"\",\"requestId\":\""+requestId
                +"\",\"amountPaise\":"+amount+"}";
    }
    private String billDailyUrl(Fixture f,LocalDate date) {
        return "/api/v1/branches/"+f.branch()+"/billing/daily?date="+date;
    }

    @Test
    void billingUsesServerFeeAndSameVisitWithIdempotentInvoiceReference()
            throws Exception {
        Fixture f=fixture(true);
        String reception=login(f),doctor=loginDoctor(f),cashier=loginBilling(f);
        UUID appt=readyForRx(f,reception,doctor,"15:00");
        var context=call("GET",url(f)+"/"+appt+"/billing-context",cashier,null);
        assertThat(context.statusCode()).isEqualTo(200);
        assertThat(context.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(context.body()).contains("\"patientId\":",
                "\"amountPaise\":50000","\"serviceCode\":\"OPD-CONSULTATION\"");
        assertThat(context.body()).contains("\"appointmentId\":\""+appt+"\"");

        String path=billPath(f,appt);
        assertThat(call("GET",path,cashier,null).statusCode()).isEqualTo(404);
        UUID request=UUID.randomUUID();
        var created=call("POST",path,cashier,billCreatePayload(request));
        assertThat(created.statusCode()).isEqualTo(201);
        assertThat(created.body()).contains("\"totalPaise\":50000",
                "\"paidPaise\":0","\"outstandingPaise\":50000",
                "\"status\":\"UNPAID\"","\"replayed\":false");
        UUID invoice=id(created.body(),"invoiceId");
        assertThat(created.body()).contains("\"appointmentId\":\""+appt+"\"");
        var replay=call("POST",path,cashier,billCreatePayload(request));
        assertThat(replay.statusCode()).isEqualTo(200);
        assertThat(replay.body()).contains("\"invoiceId\":\""+invoice+"\"",
                "\"replayed\":true");
        assertThat(call("POST",path,cashier,billCreatePayload(UUID.randomUUID()))
                .statusCode()).isEqualTo(409);
        assertThat(call("POST",path,cashier,
                "{\"requestId\":\""+UUID.randomUUID()
                +"\",\"serviceCode\":\"OPD-OVERRIDE\"}").statusCode())
                .isEqualTo(400);
        var reopened=call("GET",path,cashier,null);
        assertThat(reopened.statusCode()).isEqualTo(200);
        assertThat(reopened.body()).contains("\"invoiceId\":\""+invoice+"\"");
        assertThat(admin.queryForObject("""
            SELECT count(*) FROM appointment_proof.opd_invoice
            WHERE appointment_id=?
            """,Integer.class,appt)).isOne();
        // Server-issued invoice number is immutable and never supplied by UI.
        org.assertj.core.api.Assertions.assertThatThrownBy(()->admin.update("""
            UPDATE appointment_proof.opd_invoice SET total_paise=1
            WHERE appointment_id=?
            """,appt)).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    @Test
    void billingCashPartialAndFinalEventsAreIdempotentAndReportedByClinicDate()
            throws Exception {
        Fixture f=fixture(true);
        String reception=login(f),doctor=loginDoctor(f),cashier=loginBilling(f);
        UUID appt=readyForRx(f,reception,doctor,"15:15");
        LocalDate today=LocalDate.now(CLINIC_ZONE);
        var zero=call("GET",billDailyUrl(f,today),cashier,null);
        assertThat(zero.statusCode()).isEqualTo(200);
        assertThat(zero.body()).contains("\"invoiceCount\":0",
                "\"collectionsPaise\":0","\"billedPaise\":0");
        var created=call("POST",billPath(f,appt),cashier,
                billCreatePayload(UUID.randomUUID()));
        assertThat(created.statusCode()).isEqualTo(201);
        UUID invoice=id(created.body(),"invoiceId");
        UUID pay1=UUID.randomUUID();
        var partial=call("POST",billPath(f,appt)+"/cash",cashier,
                cashPayload(invoice,pay1,20000));
        assertThat(partial.statusCode()).isEqualTo(200);
        assertThat(partial.body()).contains("\"status\":\"PARTIAL\"",
                "\"paidPaise\":20000","\"outstandingPaise\":30000");
        var retry=call("POST",billPath(f,appt)+"/cash",cashier,
                cashPayload(invoice,pay1,20000));
        assertThat(retry.statusCode()).isEqualTo(200);
        assertThat(retry.body()).contains("\"replayed\":true",
                "\"paidPaise\":20000");
        assertThat(call("POST",billPath(f,appt)+"/cash",cashier,
                cashPayload(invoice,pay1,10000)).statusCode()).isEqualTo(409);
        assertThat(call("POST",billPath(f,appt)+"/cash",cashier,
                cashPayload(invoice,UUID.randomUUID(),30001)).statusCode())
                .isEqualTo(409);
        var paid=call("POST",billPath(f,appt)+"/cash",cashier,
                cashPayload(invoice,UUID.randomUUID(),30000));
        assertThat(paid.statusCode()).isEqualTo(200);
        assertThat(paid.body()).contains("\"status\":\"PAID\"",
                "\"paidPaise\":50000","\"outstandingPaise\":0");
        assertThat(call("POST",billPath(f,appt)+"/cash",cashier,
                cashPayload(invoice,UUID.randomUUID(),1)).statusCode())
                .isEqualTo(409);

        var report=call("GET",billDailyUrl(f,today),cashier,null);
        assertThat(report.statusCode()).isEqualTo(200);
        assertThat(report.body()).contains("\"invoiceCount\":1",
                "\"billedPaise\":50000","\"collectionsPaise\":50000",
                "\"outstandingPaise\":0");
        var otherDay=call("GET",billDailyUrl(f,today.minusDays(1)),cashier,null);
        assertThat(otherDay.statusCode()).isEqualTo(200);
        assertThat(otherDay.body()).contains("\"invoiceCount\":0",
                "\"collectionsPaise\":0");
        assertThat(admin.queryForObject("""
            SELECT count(*) FROM appointment_proof.opd_cash_event
            WHERE invoice_id=?
            """,Integer.class,invoice)).isEqualTo(2);
        org.assertj.core.api.Assertions.assertThatThrownBy(()->admin.update("""
            DELETE FROM appointment_proof.opd_cash_event WHERE invoice_id=?
            """,invoice)).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    @Test
    void billingDeniedBeforeConsultationAndAcrossTenantBranchRoles()
            throws Exception {
        Fixture f=fixture(true),foreign=fixture(true);
        String receptionist=login(f),doctor=loginDoctor(f),cashier=loginBilling(f);
        var booked=call("POST",url(f),receptionist,
                payload(f,UUID.randomUUID(),f.doctor(),f.registration(),
                    LocalDate.now(CLINIC_ZONE),"15:30"));
        assertThat(booked.statusCode()).isEqualTo(201);
        UUID appt=id(booked.body(),"appointmentId");
        String path=billPath(f,appt);
        assertThat(call("GET",url(f)+"/"+appt+"/billing-context",cashier,null)
                .statusCode()).isEqualTo(409);
        assertThat(call("POST",path,cashier,
                billCreatePayload(UUID.randomUUID())).statusCode()).isEqualTo(409);
        assertThat(call("POST",url(f)+"/"+appt+"/check-in",receptionist,
                "{\"expectedRevision\":1}").statusCode()).isEqualTo(200);
        assertThat(call("POST",path,cashier,
                billCreatePayload(UUID.randomUUID())).statusCode()).isEqualTo(409);
        assertThat(call("GET",url(f)+"/"+appt+"/billing-context",receptionist,null)
                .statusCode()).isEqualTo(403);
        assertThat(call("GET",url(f)+"/"+appt+"/billing-context",doctor,null)
                .statusCode()).isEqualTo(403);
        assertThat(call("GET",url(f)+"/"+appt+"/billing-context",
                loginBilling(foreign),null).statusCode()).isEqualTo(403);
        assertThat(call("POST",
                "/api/v1/branches/"+f.otherBranch()+"/appointments/"+appt+"/bill",
                cashier,billCreatePayload(UUID.randomUUID())).statusCode())
                .isEqualTo(403);
        assertThat(call("GET",billDailyUrl(f,LocalDate.now(CLINIC_ZONE)),
                receptionist,null).statusCode()).isEqualTo(403);
        assertThat(call("GET",
                "/api/v1/branches/"+f.otherBranch()+"/billing/daily?date="
                +LocalDate.now(CLINIC_ZONE),cashier,null).statusCode())
                .isEqualTo(403);
        assertThat(admin.queryForObject("""
            SELECT count(*) FROM appointment_proof.opd_invoice
            WHERE appointment_id=?
            """,Integer.class,appt)).isZero();
    }

    @Test
    void billingLivePermissionModuleAndLogoutRevocationDenyFinancialRecords()
            throws Exception {
        Fixture f=fixture(true);
        String receptionist=login(f),doctor=loginDoctor(f),cashier=loginBilling(f);
        UUID appt=readyForRx(f,receptionist,doctor,"15:45");
        String path=billPath(f,appt);
        var created=call("POST",path,cashier,billCreatePayload(UUID.randomUUID()));
        assertThat(created.statusCode()).isEqualTo(201);
        UUID invoice=id(created.body(),"invoiceId");

        admin.update("""
            DELETE FROM auth_role_permission WHERE tenant_id=?
             AND permission_code='OPD_PAYMENT_POST'
            """,f.tenant());
        assertThat(call("POST",path+"/cash",cashier,
                cashPayload(invoice,UUID.randomUUID(),1000)).statusCode())
                .isEqualTo(403);
        admin.update("""
            DELETE FROM auth_role_permission WHERE tenant_id=?
             AND permission_code='OPD_DAILY_REPORT_READ'
            """,f.tenant());
        assertThat(call("GET",billDailyUrl(f,LocalDate.now(CLINIC_ZONE)),
                cashier,null).statusCode()).isEqualTo(403);
        admin.update("""
            UPDATE tenant_module_state SET activated=false WHERE tenant_id=?
             AND module_code='OPD'
            """,f.tenant());
        assertThat(call("GET",path,cashier,null).statusCode()).isEqualTo(403);
        admin.update("""
            UPDATE tenant_module_state SET activated=true WHERE tenant_id=?
             AND module_code='OPD'
            """,f.tenant());
        assertThat(call("GET",path,cashier,null).statusCode()).isEqualTo(200);
        assertThat(call("POST","/api/v1/auth/logout",cashier,"{}")
                .statusCode()).isBetween(200,204);
        assertThat(call("GET",path,cashier,null).statusCode()).isEqualTo(401);
        assertThat(admin.queryForObject("""
            SELECT count(*) FROM appointment_proof.opd_cash_event
            WHERE invoice_id=?
            """,Integer.class,invoice)).isZero();
    }


}
