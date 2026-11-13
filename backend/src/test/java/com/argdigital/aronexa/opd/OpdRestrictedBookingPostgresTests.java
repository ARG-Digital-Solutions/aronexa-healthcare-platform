package com.argdigital.aronexa.opd;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import com.argdigital.aronexa.auth.TenantAuthorization;
import com.argdigital.aronexa.moduleregistry.ModuleAccessService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Restricted SQL command evidence only on automatically disposed PostgreSQL 17.
 * Uses independent LOGIN and random ephemeral key; no production Flyway,
 * clinic database, developer login or publicly routable HTTP endpoint.
 */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties={"server.address=127.0.0.1","server.error.include-message=never"})
@ActiveProfiles({"test","opd-restricted-candidate"})
@Import(OpdRestrictedBookingPostgresTests.RestrictedHttpController.class)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OpdRestrictedBookingPostgresTests {
    @Container @ServiceConnection
    static PostgreSQLContainer pg=new PostgreSQLContainer("postgres:17-alpine")
            .withInitScript("db/test/role-bootstrap.sql")
            .withDatabaseName("aronexa_r110b2_disposable_only")
            .withUsername("aronexa_r110b2_temporary")
            .withPassword("testcontainer-only-not-production");

    @Autowired JdbcTemplate admin;
    @Autowired PasswordEncoder passwords;
    @Autowired ApplicationContext application;
    @Autowired org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping mappings;
    @Value("$"+"{local.server.port}") int port;
    final HttpClient http=HttpClient.newHttpClient();
    private static volatile OpdRestrictedSqlAdapter restrictedAdapter;
    private JdbcTemplate limited;
    private byte[] hmacKey;
    private String runtimePassword;
    private final LocalDate day=LocalDate.of(2026,11,13);
    private final LocalTime slot=LocalTime.of(10,0);

    private static Path reviewFile(String filename) {
        Path path=Path.of("deploy","opd","review-only",filename).toAbsolutePath();
        if(!Files.isRegularFile(path)) {
            path=Path.of("..","deploy","opd","review-only",filename).toAbsolutePath();
        }
        if(!Files.isRegularFile(path)) throw new IllegalStateException("Missing reviewed SQL: "+filename);
        return path;
    }

    private void executeOnlyInTestcontainer(String file) throws Exception {
        try(Connection c=admin.getDataSource().getConnection()) {
            ScriptUtils.executeSqlScript(c,new EncodedResource(
                new FileSystemResource(reviewFile(file))),false,false,
                ScriptUtils.DEFAULT_COMMENT_PREFIX,ScriptUtils.EOF_STATEMENT_SEPARATOR,
                ScriptUtils.DEFAULT_BLOCK_COMMENT_START_DELIMITER,
                ScriptUtils.DEFAULT_BLOCK_COMMENT_END_DELIMITER);
        }
    }

    @BeforeAll
    void stageStrictlyDisposableRolesAndSchema() throws Exception {
        executeOnlyInTestcontainer("V11__opd_appointment_schema_candidate.sql");
        executeOnlyInTestcontainer("R110B2__restricted_booking_writer_candidate.sql");
        executeOnlyInTestcontainer("R110B3B__ordered_session_gate_candidate.sql");
        hmacKey=new byte[32];
        new SecureRandom().nextBytes(hmacKey);
        admin.update("""
            INSERT INTO opd_candidate.command_key(id,secret,enabled,expires_at)
            VALUES (1,?,true,clock_timestamp()+interval '1 hour')
            """,hmacKey);
        // Password is random AND only generated inside this throwaway container.
        byte[] raw=new byte[24];
        new SecureRandom().nextBytes(raw);
        String password=HexFormat.of().formatHex(raw);
        runtimePassword=password;
        admin.execute("ALTER ROLE aronexa_opd_runtime LOGIN PASSWORD '"+password+"'");
        var restricted=new DriverManagerDataSource(
                pg.getJdbcUrl(),"aronexa_opd_runtime",password);
        limited=new JdbcTemplate(restricted);
        restrictedAdapter=new OpdRestrictedSqlAdapter(restricted,hmacKey,1,
                java.time.Clock.systemUTC());
        assertThat(application.getBeansOfType(RestrictedHttpController.class)).hasSize(1);
        assertThat(mappings.getHandlerMethods().entrySet().stream()
            .anyMatch(entry -> entry.getKey().getMethodsCondition().getMethods()
                    .contains(org.springframework.web.bind.annotation.RequestMethod.POST)
                && entry.getKey().getPatternValues().contains(
                    "/api/v1/branches/{branchId}/opd-restricted/appointments")
                && entry.getValue().getBeanType()
                    .equals(RestrictedHttpController.class)))
            .as("R1-10B3A must map a real POST, not an /error redispatch")
            .isTrue();
        admin.update("""
            INSERT INTO public.auth_permission(code,description) VALUES
             ('APPOINTMENT_BOOK','Disposable R110B2 book test'),
             ('APPOINTMENT_CHECK_IN','Disposable R110B2 check-in test')
            ON CONFLICT(code) DO NOTHING
            """);
        admin.update("""
           UPDATE public.module_registry SET implemented=true,released=true
           WHERE code IN ('OPD','PATIENT_REGISTRATION')
           """);
    }

    /**
     * This route is present ONLY in a disposable JUnit @Import + test profile.
     * It exercises real bearer auth + production-source command/adapter +
     * restricted SQL, unlike the older privileged candidate HTTP harness.
     * It is never on the production Spring component scan or application JAR.
     */
    @RestController
    @Profile("test & opd-restricted-candidate & !dev & !prod")
    @RequestMapping("/api/v1/branches/{branchId}/opd-restricted/appointments")
    static final class RestrictedHttpController {
        private final TenantAuthorization authorization;
        private final ModuleAccessService modules;
        private final OpdRegistrationHandoffService handoff;

        RestrictedHttpController(TenantAuthorization authorization,
                ModuleAccessService modules,OpdRegistrationHandoffService handoff) {
            this.authorization=authorization;
            this.modules=modules;
            this.handoff=handoff;
        }

        record Book(UUID registrationId,UUID doctorMembershipId,
                LocalDate visitDate,LocalTime slotStart,UUID requestId) {}
        record CheckIn(int expectedRevision,UUID requestId) {}
        record Failure(String code) {}

        private OpdBookingCommand command() {
            var adapter=restrictedAdapter;
            if(adapter==null) throw new IllegalStateException("Test PG17 adapter not initialized");
            return new OpdBookingCommand(authorization,modules,handoff,adapter);
        }

        @PostMapping
        ResponseEntity<OpdBookingCommand.BookingReceipt> book(
                @PathVariable UUID branchId,@RequestBody Book req) {
            var result=command().book(new OpdBookingCommand.BookingRequest(
                    branchId,req.registrationId(),req.doctorMembershipId(),
                    req.visitDate(),req.slotStart(),req.requestId()));
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
        }

        @PostMapping("/{appointmentId}/check-in")
        ResponseEntity<OpdBookingCommand.BookingReceipt> checkIn(
                @PathVariable UUID branchId,@PathVariable UUID appointmentId,
                @RequestBody CheckIn req) {
            var result=command().checkIn(new OpdBookingCommand.CheckInRequest(
                    branchId,appointmentId,req.expectedRevision(),req.requestId()));
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
        }

        @ExceptionHandler(AccessDeniedException.class)
        ResponseEntity<Failure> denied(AccessDeniedException ignored) {
            return ResponseEntity.status(403).cacheControl(CacheControl.noStore())
                    .body(new Failure("OPD_ACCESS_DENIED"));
        }

        @ExceptionHandler(OpdRestrictedSqlAdapter.Rejected.class)
        ResponseEntity<Failure> sqlDenied(OpdRestrictedSqlAdapter.Rejected ignored) {
            return ResponseEntity.status(409).cacheControl(CacheControl.noStore())
                    .body(new Failure("OPD_COMMAND_REJECTED"));
        }

        @ExceptionHandler({OpdBookingCommand.InvalidBooking.class,
                           IllegalArgumentException.class})
        ResponseEntity<Failure> invalid(RuntimeException ignored) {
            return ResponseEntity.badRequest().cacheControl(CacheControl.noStore())
                    .body(new Failure("INVALID_OPD_REQUEST"));
        }
    }

    record Fixture(UUID tenant,UUID branch,UUID registration,UUID patient,
                   UUID staff,UUID session,UUID doctor,
                   String tenantCode,String username) {}

    private UUID user(UUID tenant) {
        UUID user=UUID.randomUUID(),member=UUID.randomUUID();
        admin.update("""
            INSERT INTO public.app_user(id,username,password_hash,must_change_password)
            VALUES (?,?,?,false)
            """,user,"b2_"+user.toString().substring(0,12),
            passwords.encode("FixtureOnlyR110B3A-Password_321!"));
        admin.update("""
            INSERT INTO public.user_tenant_membership(id,user_id,tenant_id)
            VALUES(?,?,?)
            """,member,user,tenant);
        return member;
    }

    private Fixture fixture(){
        UUID tenant=UUID.randomUUID(),facility=UUID.randomUUID();
        UUID branch=UUID.randomUUID(),patient=UUID.randomUUID();
        UUID registration=UUID.randomUUID(),session=UUID.randomUUID();
        String tenantCode="b2_"+tenant.toString().substring(0,10);
        admin.update("INSERT INTO public.tenant(id,code,display_name) VALUES(?,?,?)",
                tenant,tenantCode,"FICTIONAL tenant");
        admin.update("""
            INSERT INTO public.tenant_module_state
              (tenant_id,module_code,entitled,activated,clinically_authorized)
            VALUES (?,'OPD',true,true,true),
                   (?,'PATIENT_REGISTRATION',true,true,true)
            """,tenant,tenant);
        admin.update("""
            INSERT INTO public.facility(id,tenant_id,code,display_name,facility_kind)
            VALUES (?,?,?,'FICTIONAL facility','CLINIC')
            """,facility,tenant,"f_"+facility.toString().substring(0,8));
        admin.update("""
            INSERT INTO public.branch(id,tenant_id,facility_id,code,display_name)
            VALUES (?,?,?,?,'FICTIONAL branch')
            """,branch,tenant,facility,"b_"+branch.toString().substring(0,8));
        UUID staff=user(tenant),doctor=user(tenant);
        String username=admin.queryForObject("""
            SELECT u.username FROM public.app_user u
            JOIN public.user_tenant_membership m ON m.user_id=u.id
            WHERE m.tenant_id=? AND m.id=?
            """,String.class,tenant,staff);
        UUID receptionRole=UUID.randomUUID(),doctorRole=UUID.randomUUID();
        admin.update("""
            INSERT INTO public.auth_role(id,tenant_id,code,display_name)
            VALUES (?,?,'RECEPTION','FICTIONAL receptionist')
            """,receptionRole,tenant);
        admin.update("""
            INSERT INTO public.auth_role(id,tenant_id,code,display_name)
            VALUES (?,?,'DOCTOR','FICTIONAL doctor')
            """,doctorRole,tenant);
        admin.update("""
            INSERT INTO public.auth_membership_role(tenant_id,membership_id,role_id)
            VALUES(?,?,?)
            """,tenant,staff,receptionRole);
        admin.update("""
            INSERT INTO public.auth_membership_role(tenant_id,membership_id,role_id)
            VALUES(?,?,?)
            """,tenant,doctor,doctorRole);
        admin.update("""
            INSERT INTO public.auth_role_permission(tenant_id,role_id,permission_code)
            VALUES (?,?,'APPOINTMENT_BOOK'),
                   (?,?,'APPOINTMENT_CHECK_IN'),
                   (?,?,'PATIENT_REGISTRATION_READ')
            """,tenant,receptionRole,tenant,receptionRole,tenant,receptionRole);
        admin.update("""
            INSERT INTO public.org_membership_branch_scope
              (tenant_id,membership_id,branch_id)
            VALUES (?,?,?),(?,?,?)
            """,tenant,staff,branch,tenant,doctor,branch);
        admin.update("""
            INSERT INTO public.auth_session(id,tenant_id,membership_id,
                       token_sha256,expires_at)
            VALUES (?,?,?,?,clock_timestamp()+interval '1 hour')
            """,session,tenant,staff,
                UUID.randomUUID().toString().replace("-","")
                 +UUID.randomUUID().toString().replace("-",""));
        admin.update("""
            INSERT INTO public.patient(id,tenant_id,display_name)
            VALUES (?,?,'FICTIONAL - NOT A PATIENT')
            """,patient,tenant);
        admin.update("""
            INSERT INTO public.patient_registration
              (id,tenant_id,patient_id,branch_id,registration_source,
               registered_by_membership_id)
            VALUES (?,?,?,?,'RECEPTION',?)
            """,registration,tenant,patient,branch,staff);
        return new Fixture(tenant,branch,registration,patient,staff,session,
                doctor,tenantCode,username);
    }

    private Map<String,Object> book(Fixture f,UUID request,UUID appointment,
            UUID nonce,UUID patient,String proofOverride,LocalTime at) {
        long expiration=Instant.now().plusSeconds(30).getEpochSecond();
        String proof=proofOverride==null
            ?OpdCommandProof.booking(hmacKey,1,f.tenant(),f.staff(),
               f.session(),f.branch(),f.registration(),patient,f.doctor(),
               day,at,request,appointment,nonce,expiration)
            :proofOverride;
        return limited.queryForMap("""
            SELECT * FROM opd_candidate.book_guarded(
             ?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """,f.tenant(),f.staff(),f.session(),f.branch(),f.registration(),
            patient,f.doctor(),day,at,request,appointment,1,nonce,expiration,proof);
    }

    private Map<String,Object> book(Fixture f,UUID request,UUID appointment) {
        return book(f,request,appointment,UUID.randomUUID(),f.patient(),null,slot);
    }

    private Map<String,Object> checkIn(Fixture f,UUID appointment,UUID request,
            int expected,String proofOverride) {
        long expiration=Instant.now().plusSeconds(30).getEpochSecond();
        UUID nonce=UUID.randomUUID();
        String proof=proofOverride==null
            ?OpdCommandProof.checkIn(hmacKey,1,f.tenant(),f.staff(),
                f.session(),f.branch(),appointment,expected,request,nonce,expiration)
            :proofOverride;
        return limited.queryForMap("""
            SELECT * FROM opd_candidate.checkin_guarded(?,?,?,?,?,?,?,?,?,?,?)
            """,f.tenant(),f.staff(),f.session(),f.branch(),appointment,
             expected,request,1,nonce,expiration,proof);
    }

    private static final String FIXTURE_PASSWORD="FixtureOnlyR110B3A-Password_321!";

    private HttpResponse<String> httpPost(String path,String json,String bearer)
            throws Exception {
        var builder=HttpRequest.newBuilder(URI.create(
                "http://127.0.0.1:"+port+path))
                .header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json));
        if(bearer!=null) builder.header("Authorization","Bearer "+bearer);
        return http.send(builder.build(),HttpResponse.BodyHandlers.ofString());
    }

    private String login(Fixture f) throws Exception {
        String body=String.format(java.util.Locale.ROOT,
                "{\"tenantCode\":\"%s\",\"username\":\"%s\",\"password\":\"%s\"}",
                f.tenantCode(),f.username(),FIXTURE_PASSWORD);
        var result=httpPost("/api/v1/auth/login",body,null);
        assertThat(result.statusCode()).as("Real DB-backed login").isEqualTo(200);
        var matcher=Pattern.compile(
                "\"accessToken\"\\s*:\\s*\"([A-Za-z0-9_-]{43})\"")
                .matcher(result.body());
        assertThat(matcher.find()).isTrue();
        String token=matcher.group(1);
        var me=http.send(HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:"+port+"/api/v1/auth/me"))
                .header("Authorization","Bearer "+token).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(me.statusCode()).as("Token authorizes /auth/me").isEqualTo(200);
        assertThat(me.body()).contains(f.staff().toString());
        return token;
    }

    private static String bookingJson(Fixture f,UUID request) {
        return String.format(java.util.Locale.ROOT,
                "{\"registrationId\":\"%s\",\"doctorMembershipId\":\"%s\","
                +"\"visitDate\":\"2026-11-13\",\"slotStart\":\"10:30:00\","
                +"\"requestId\":\"%s\"}",
                f.registration(),f.doctor(),request);
    }

    private static UUID receiptId(String json) {
        var matcher=Pattern.compile(
                "\"appointmentId\"\\s*:\\s*\"([0-9a-f-]{36})\"")
                .matcher(json);
        assertThat(matcher.find()).as("Actual restricted SQL appointment receipt").isTrue();
        return UUID.fromString(matcher.group(1));
    }

    @Test
    void realBearerHttpBooksChecksInAndReplaysAfterLogoutAndFreshLogin()
            throws Exception {
        Fixture f=fixture();
        String token=login(f);
        String path="/api/v1/branches/"+f.branch()+"/opd-restricted/appointments";
        UUID request=UUID.randomUUID();
        var booked=httpPost(path,bookingJson(f,request),token);
        assertThat(booked.statusCode()).as(booked.body()).isEqualTo(200);
        assertThat(booked.body()).contains("\"stage\":\"BOOKED\"",
                f.patient().toString(),f.registration().toString());
        assertThat(booked.body()).doesNotContain("sessionId","accessToken",
                "password");
        assertThat(booked.headers().firstValue("Cache-Control"))
                .contains("no-store");
        UUID id=receiptId(booked.body());
        var checked=httpPost(path+"/"+id+"/check-in",
                "{\"expectedRevision\":1,\"requestId\":\""+
                        UUID.randomUUID()+"\"}",token);
        assertThat(checked.statusCode()).as(checked.body()).isEqualTo(200);
        assertThat(checked.body()).contains("\"stage\":\"CHECKED_IN\"",
                "\"revision\":2");
        var logout=httpPost("/api/v1/auth/logout","{}",token);
        assertThat(logout.statusCode()).isEqualTo(204);
        var unauthorized=httpPost(path,bookingJson(f,request),token);
        assertThat(unauthorized.statusCode()).isNotEqualTo(200);
        String newToken=login(f);
        assertThat(newToken).isNotEqualTo(token);
        var replay=httpPost(path,bookingJson(f,request),newToken);
        assertThat(replay.statusCode()).as(replay.body()).isEqualTo(200);
        assertThat(replay.body()).contains("\"stage\":\"CHECKED_IN\"",
                "\"replayed\":true");
        assertThat(receiptId(replay.body())).isEqualTo(id);
        assertThat(admin.queryForObject("""
             SELECT count(*) FROM opd_candidate.appointment_event
             WHERE appointment_id=?
             """,Integer.class,id)).isEqualTo(2);
    }

    @Test
    void realHttpRejectsAnonymousForgedRegistrationCrossBranchAndRevokedToken()
            throws Exception {
        Fixture f=fixture();
        String path="/api/v1/branches/"+f.branch()+"/opd-restricted/appointments";
        String good=bookingJson(f,UUID.randomUUID());
        assertThat(httpPost(path,good,null).statusCode()).isNotEqualTo(200);
        String token=login(f);
        assertThat(httpPost(path,good.replace(f.registration().toString(),
                UUID.randomUUID().toString()),token).statusCode())
                .isEqualTo(403);
        String wrong="/api/v1/branches/"+UUID.randomUUID()
                +"/opd-restricted/appointments";
        assertThat(httpPost(wrong,good,token).statusCode()).isEqualTo(403);
        admin.update("""
            UPDATE public.auth_session SET revoked_at=clock_timestamp()
            WHERE tenant_id=? AND membership_id=?
            """,f.tenant(),f.staff());
        assertThat(httpPost(path,good,token).statusCode()).isNotEqualTo(200);
        assertThat(admin.queryForObject("""
            SELECT count(*) FROM opd_candidate.appointment
            WHERE tenant_id=? AND branch_id=?
            """,Integer.class,f.tenant(),f.branch())).isZero();
    }

    @Test
    void realHttpRejectsRevokedBookingPermissionAndStaleCheckinRevision()
            throws Exception {
        Fixture revoked=fixture();
        String token=login(revoked);
        String path="/api/v1/branches/"+revoked.branch()
                +"/opd-restricted/appointments";
        admin.update("""
            DELETE FROM public.auth_role_permission rp
            USING public.auth_membership_role mr
            WHERE rp.tenant_id=mr.tenant_id AND rp.role_id=mr.role_id
              AND mr.tenant_id=? AND mr.membership_id=?
              AND rp.permission_code='APPOINTMENT_BOOK'
            """,revoked.tenant(),revoked.staff());
        var rejected=httpPost(path,bookingJson(revoked,UUID.randomUUID()),token);
        assertThat(rejected.statusCode()).isNotEqualTo(200);
        assertThat(admin.queryForObject("""
            SELECT count(*) FROM opd_candidate.appointment WHERE tenant_id=?
            """,Integer.class,revoked.tenant())).isZero();

        Fixture valid=fixture();
        String allowed=login(valid);
        String allowedPath="/api/v1/branches/"+valid.branch()
                +"/opd-restricted/appointments";
        var booking=httpPost(allowedPath,bookingJson(valid,UUID.randomUUID()),allowed);
        assertThat(booking.statusCode()).as(booking.body()).isEqualTo(200);
        UUID appointment=receiptId(booking.body());
        var stale=httpPost(allowedPath+"/"+appointment+"/check-in",
                "{\"expectedRevision\":9,\"requestId\":\""+
                        UUID.randomUUID()+"\"}",allowed);
        assertThat(stale.statusCode()).isEqualTo(409);
        assertThat(admin.queryForObject("""
            SELECT revision FROM opd_candidate.appointment WHERE id=?
            """,Integer.class,appointment)).isEqualTo(1);
        assertThat(admin.queryForObject("""
            SELECT count(*) FROM opd_candidate.appointment_event WHERE appointment_id=?
            """,Integer.class,appointment)).isEqualTo(1);
    }

    @Test
    void sourceOwnedAdapterBooksAndChecksInThroughRestrictedJdbcRole() {
        Fixture f=fixture();
        var restricted=new DriverManagerDataSource(
                pg.getJdbcUrl(),"aronexa_opd_runtime",runtimePassword);
        var adapter=new OpdRestrictedSqlAdapter(restricted,hmacKey,1,
                java.time.Clock.systemUTC());
        var actor=new com.argdigital.aronexa.auth.AuthPrincipal(
                UUID.randomUUID(),f.tenant(),f.staff(),"fictional_reception",
                false,java.util.List.of("RECEPTION"),
                java.util.List.of("APPOINTMENT_BOOK","APPOINTMENT_CHECK_IN"),
                f.session());
        var subject=new OpdRegistrationHandoffService.VerifiedVisitSubject(
                f.tenant(),f.branch(),f.registration(),f.patient(),
                f.staff(),f.session());
        var request=new OpdBookingCommand.BookingRequest(
                f.branch(),f.registration(),f.doctor(),day,
                LocalTime.of(13,15),UUID.randomUUID());
        var booked=adapter.bookWithinAuthorizedTransaction(actor,subject,request);
        assertThat(booked.patientId()).isEqualTo(f.patient());
        assertThat(booked.registrationId()).isEqualTo(f.registration());
        assertThat(booked.doctorMembershipId()).isEqualTo(f.doctor());
        assertThat(booked.stage()).isEqualTo("BOOKED");
        var checked=adapter.checkInWithinAuthorizedTransaction(actor,
                new OpdBookingCommand.CheckInRequest(
                        f.branch(),booked.appointmentId(),1,UUID.randomUUID()));
        assertThat(checked.stage()).isEqualTo("CHECKED_IN");
        assertThat(checked.revision()).isEqualTo(2);
        assertThat(admin.queryForObject("""
            SELECT count(*) FROM opd_candidate.appointment_event
            WHERE appointment_id=?
            """,Integer.class,booked.appointmentId())).isEqualTo(2);
    }

    @Test
    void actualRestrictedLoginBooksChecksInAndAuditsCommittedAppointment() throws Exception {
        Fixture f=fixture();
        UUID id=UUID.randomUUID();
        var receipt=book(f,UUID.randomUUID(),id);
        assertThat(receipt.get("result_appointment_id")).isEqualTo(id);
        assertThat(receipt.get("result_stage")).isEqualTo("BOOKED");
        UUID request=UUID.randomUUID();
        var checked=checkIn(f,id,request,1,null);
        assertThat(checked.get("result_stage")).isEqualTo("CHECKED_IN");
        var replay=checkIn(f,id,request,1,null);
        assertThat(replay.get("result_replayed")).isEqualTo(true);
        // Separate physical database connection verifies committed data/audit.
        try(Connection c=DriverManager.getConnection(
                pg.getJdbcUrl(),pg.getUsername(),pg.getPassword())){
            try(var ps=c.prepareStatement("""
                SELECT status,revision FROM opd_candidate.appointment WHERE id=?
                """)){
                ps.setObject(1,id);
                try(var rs=ps.executeQuery()){
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getString(1)).isEqualTo("CHECKED_IN");
                    assertThat(rs.getInt(2)).isEqualTo(2);
                }
            }
        }
        assertThat(admin.queryForObject("""
            SELECT count(*) FROM opd_candidate.appointment_event
            WHERE appointment_id=?
            """,Integer.class,id)).isEqualTo(2);
    }

    @Test
    void stolenRuntimeLoginCannotReadOrModifyTablesEvenWithForgedTenantGuc() throws Exception {
        Fixture f=fixture();
        assertThatThrownBy(()->limited.queryForList("""
            SELECT * FROM opd_candidate.appointment
            """)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->limited.update("""
            INSERT INTO opd_candidate.command_key(id,secret,expires_at)
            VALUES(7,?,clock_timestamp()+interval '1 hour')
            """,hmacKey)).isInstanceOf(DataAccessException.class);
        // Even after spoofing a tenant GUC, the restricted LOGIN has no DML.
        try(Connection connection=DriverManager.getConnection(pg.getJdbcUrl(),
                "aronexa_opd_runtime",runtimePassword);
            var statement=connection.createStatement()){
            statement.execute("SELECT set_config('aronexa.tenant_id','"
                    +f.tenant()+"',false)");
            assertThatThrownBy(()->statement.executeUpdate(
                    "INSERT INTO opd_candidate.appointment(id) VALUES "
                    +"('00000000-0000-0000-0000-000000000001')"))
                .isInstanceOf(SQLException.class);
        }
        assertThat(admin.queryForObject("""
            SELECT rolbypassrls OR rolsuper FROM pg_catalog.pg_roles
            WHERE rolname='aronexa_opd_runtime'
            """,Boolean.class)).isFalse();
    }

    @Test
    void forgeryWrongRegistrationPatientAndReuseNonceCannotBook(){
        Fixture f=fixture();
        assertThatThrownBy(()->book(f,UUID.randomUUID(),UUID.randomUUID(),
            UUID.randomUUID(),f.patient(),"0".repeat(64),slot))
            .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->book(f,UUID.randomUUID(),UUID.randomUUID(),
            UUID.randomUUID(),UUID.randomUUID(),null,slot))
            .isInstanceOf(DataAccessException.class);
        UUID nonce=UUID.randomUUID();
        book(f,UUID.randomUUID(),UUID.randomUUID(),nonce,f.patient(),null,slot);
        assertThatThrownBy(()->book(f,UUID.randomUUID(),UUID.randomUUID(),
            nonce,f.patient(),null,LocalTime.of(10,15)))
            .isInstanceOf(DataAccessException.class);
    }

    @Test
    void idempotentBookingAndDoubleDoctorSlotConflictAreDatabaseEnforced(){
        Fixture f=fixture();
        UUID request=UUID.randomUUID(),id=UUID.randomUUID();
        book(f,request,id);
        var retry=book(f,request,UUID.randomUUID());
        assertThat(retry.get("result_appointment_id")).isEqualTo(id);
        assertThat(retry.get("result_replayed")).isEqualTo(true);
        assertThatThrownBy(()->book(f,UUID.randomUUID(),UUID.randomUUID()))
            .isInstanceOf(DataAccessException.class);
    }

    @Test
    void wrongSqlIdentityOrMissingProofKeyNeverConstructsRuntimeWriter() {
        assertThatThrownBy(()->new OpdRestrictedSqlAdapter(
                admin.getDataSource(),hmacKey,1,java.time.Clock.systemUTC()))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->new OpdRestrictedSqlAdapter(
                new DriverManagerDataSource(pg.getJdbcUrl(),
                    "aronexa_opd_runtime",runtimePassword),
                null,1,java.time.Clock.systemUTC()))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void branchOrModuleRevocationAndUnassignedDoctorDenyBeforeWrite() {
        Fixture a=fixture();
        admin.update("""
            UPDATE public.tenant_module_state
               SET activated=false
             WHERE tenant_id=? AND module_code='OPD'
            """,a.tenant());
        assertThatThrownBy(()->book(a,UUID.randomUUID(),UUID.randomUUID()))
            .isInstanceOf(DataAccessException.class);

        Fixture b=fixture();
        admin.update("""
            DELETE FROM public.org_membership_branch_scope
            WHERE tenant_id=? AND membership_id=? AND branch_id=?
            """,b.tenant(),b.staff(),b.branch());
        assertThatThrownBy(()->book(b,UUID.randomUUID(),UUID.randomUUID()))
            .isInstanceOf(DataAccessException.class);

        Fixture c=fixture();
        admin.update("""
            DELETE FROM public.auth_membership_role
            WHERE tenant_id=? AND membership_id=?
            """,c.tenant(),c.doctor());
        assertThatThrownBy(()->book(c,UUID.randomUUID(),UUID.randomUUID()))
            .isInstanceOf(DataAccessException.class);
    }

    @Test
    void revocationPreventsBookingAndCheckInEvenWithValidSignedCommand(){
        Fixture f=fixture();
        UUID id=UUID.randomUUID();
        book(f,UUID.randomUUID(),id);
        admin.update("""
            UPDATE public.auth_session SET revoked_at=clock_timestamp()
            WHERE id=?
            """,f.session());
        assertThatThrownBy(()->book(f,UUID.randomUUID(),UUID.randomUUID(),
             UUID.randomUUID(),f.patient(),null,LocalTime.of(10,15)))
            .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->checkIn(f,id,UUID.randomUUID(),1,null))
            .isInstanceOf(DataAccessException.class);
        assertThat(admin.queryForObject("""
            SELECT revision FROM opd_candidate.appointment WHERE id=?
            """,Integer.class,id)).isEqualTo(1);
    }
}
