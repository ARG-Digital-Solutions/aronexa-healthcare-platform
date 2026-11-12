package com.argdigital.aronexa.appointment;

import com.argdigital.aronexa.auth.AuthPrincipal;
import com.argdigital.aronexa.auth.TenantAuthorization;
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
@Import(AppointmentCandidateHttpAcceptanceTests.CandidateController.class)
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

    @BeforeAll
    void installOnDisposablePostgresOnly() throws Exception {
        assertThat(postgres.isRunning()).isTrue();
        assertThat(postgres.getDatabaseName()).isEqualTo("aronexa_r104_disposable_only");
        Path base=Path.of(System.getProperty("basedir",
                System.getProperty("user.dir"))).toAbsolutePath();
        assertThat(Files.exists(base.resolve("src/test/resources/db/test/appointment-candidate-r104.sql"))).isTrue();
        try(Connection owner=ownerDataSource.getConnection()) {
            owner.setAutoCommit(false);
            try {
                for(String file:new String[]{"db/test/appointment-persistence-proof.sql",
                        "db/test/appointment-candidate-r104.sql"}) {
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
                "APPOINTMENT_READ","APPOINTMENT_CHECK_IN"}) {
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
}
