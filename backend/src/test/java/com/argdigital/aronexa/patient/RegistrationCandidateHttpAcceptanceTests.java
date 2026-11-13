package com.argdigital.aronexa.patient;

import com.argdigital.aronexa.auth.TenantAuthorization;
import com.argdigital.aronexa.opd.OpdRegistrationHandoffService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real HTTP registration acceptance on disposable PostgreSQL only.
 *
 * Controller and wiring exist exclusively under src/test/java, not the
 * production artifact; active profile requires test + registration-candidate
 * and forbids both dev and prod. No clinical endpoint is published by this.
 */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties="server.address=127.0.0.1")
@ActiveProfiles({"test","registration-candidate"})
@Import({RegistrationCandidateHttpAcceptanceTests.CandidateConfiguration.class,
        RegistrationCandidateHttpAcceptanceTests.CandidateController.class,
        RegistrationCandidateHttpAcceptanceTests.OpdHandoffCandidateController.class})
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RegistrationCandidateHttpAcceptanceTests {
    private static final byte[] PROOF=new byte[32];
    private static final byte[] IDEM=new byte[32];
    static {
        SecureRandom r=new SecureRandom();
        r.nextBytes(PROOF);
        r.nextBytes(IDEM);
    }

    @Container @ServiceConnection
    static PostgreSQLContainer postgres=new PostgreSQLContainer("postgres:17-alpine")
            .withInitScript("db/test/role-bootstrap.sql")
            .withDatabaseName("aronexa_registration_candidate_http")
            .withUsername("aronexa_registration_candidate_http")
            .withPassword("ephemeral-test-owner-only");

    @Autowired JdbcTemplate admin;
    @Autowired PasswordEncoder passwords;
    @Autowired javax.sql.DataSource ownerDataSource;
    @Autowired org.springframework.context.ApplicationContext applicationContext;
    @Autowired org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping handlerMapping;
    @Value("$"+"{local.server.port}") int port;
    private final HttpClient http=HttpClient.newHttpClient();

    /** Only loaded inside this single isolated test class. */
    @TestConfiguration(proxyBeanMethods=false)
    @Profile("test & registration-candidate & !dev & !prod")
    static class CandidateConfiguration {
        @Bean RegistrationWriteExecutor candidateRegistrationWriter(
                TenantAuthorization authorization, PatientSqlScope scope,
                @Qualifier("patientJdbcTemplate") JdbcTemplate patientJdbc,
                @Qualifier("patientTransactionManager") PlatformTransactionManager manager) {
            return new RegistrationWriteExecutor(
                    authorization,scope,patientJdbc,manager,IDEM,PROOF,1,
                    java.time.Clock.systemUTC());
        }
    }

    /** Explicitly test-only HTTP adapter. No equivalent class exists in src/main. */
    @RestController
    @Profile("test & registration-candidate & !dev & !prod")
    @RequestMapping("/api/v1/branches/{branchId}/patient-registrations")
    static class CandidateController {
        private final RegistrationWriteExecutor executor;
        CandidateController(RegistrationWriteExecutor executor) {
            this.executor=executor;
        }
        record Register(String displayName,
                        PatientRegistrationRules.BirthDatePrecision birthDatePrecision,
                        java.time.LocalDate dateOfBirth) {}
        record Error(String code) {}
        @PostMapping
        ResponseEntity<RegistrationWriteExecutor.Receipt> create(
                @PathVariable UUID branchId,
                @RequestHeader("Idempotency-Key") String key,
                @RequestBody Register intake) {
            if(intake==null) throw new IllegalArgumentException();
            var result=executor.register(branchId,
                    new PatientRegistrationRules.Intake(intake.displayName(),
                            intake.birthDatePrecision(),intake.dateOfBirth()),key);
            return ResponseEntity.status(result.replayed()?HttpStatus.OK:HttpStatus.CREATED)
                    .cacheControl(CacheControl.noStore()).body(result);
        }
        @ExceptionHandler({IllegalArgumentException.class,MissingRequestHeaderException.class,
                org.springframework.http.converter.HttpMessageNotReadableException.class})
        ResponseEntity<Error> bad(Exception ignored) {
            return ResponseEntity.badRequest().cacheControl(CacheControl.noStore())
                    .body(new Error("INVALID_REGISTRATION_REQUEST"));
        }
        @ExceptionHandler(RegistrationWriteExecutor.Rejected.class)
        ResponseEntity<Error> rejected(RegistrationWriteExecutor.Rejected error) {
            HttpStatus status=switch(error.code()) {
                case "IDEMPOTENCY_CONFLICT","DUPLICATE_REVIEW_REQUIRED" -> HttpStatus.CONFLICT;
                case "INVALID_REGISTRATION_REQUEST" -> HttpStatus.BAD_REQUEST;
                default -> HttpStatus.SERVICE_UNAVAILABLE;
            };
            return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                    .body(new Error(error.code()));
        }
        @ExceptionHandler(AccessDeniedException.class)
        ResponseEntity<Error> forbidden(AccessDeniedException ignored) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .cacheControl(CacheControl.noStore())
                    .body(new Error("REGISTRATION_DENIED"));
        }
    }

    /**
     * R1-10A test-only HTTP adapter for the actual production-source handoff
     * service. The adapter is NOT included in the deployed main application.
     */
    @RestController
    @Profile("test & registration-candidate & !dev & !prod")
    @RequestMapping("/api/v1/branches/{branchId}/opd/registration-handoff")
    static class OpdHandoffCandidateController {
        private final OpdRegistrationHandoffService handoff;
        OpdHandoffCandidateController(OpdRegistrationHandoffService handoff) {
            this.handoff=handoff;
        }
        record Handoff(UUID tenantId,UUID branchId,UUID registrationId,UUID patientId) {}
        record Denied(String code) {}
        @GetMapping("/{registrationId}")
        ResponseEntity<Handoff> resolve(@PathVariable UUID branchId,
                                        @PathVariable UUID registrationId) {
            var verified=handoff.resolveForBooking(branchId,registrationId);
            return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                    .body(new Handoff(verified.tenantId(),verified.branchId(),
                            verified.registrationId(),verified.patientId()));
        }
        @ExceptionHandler(AccessDeniedException.class)
        ResponseEntity<Denied> deny(AccessDeniedException ignored) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .cacheControl(CacheControl.noStore())
                    .body(new Denied("OPD_HANDOFF_DENIED"));
        }
    }

    @BeforeAll
    void installOnlyInDisposableContainer() throws Exception {
        // A nested test-only @RestController is not reliably discovered by
        // default component scanning. Require exactly one registered adapter
        // and a real POST handler instead of accepting an /error redispatch
        // that can misleadingly surface as HTTP 401.
        assertThat(applicationContext.getBeansOfType(CandidateController.class))
                .hasSize(1);
        assertThat(handlerMapping.getHandlerMethods().entrySet().stream()
                .anyMatch(entry -> entry.getKey().getMethodsCondition()
                        .getMethods().contains(org.springframework.web.bind.annotation.RequestMethod.POST)
                    && entry.getKey().getPatternValues().contains(
                        "/api/v1/branches/{branchId}/patient-registrations")
                    && entry.getValue().getBeanType().equals(CandidateController.class)))
                .as("Candidate POST must be mapped to test-only controller")
                .isTrue();
        Path dir=Path.of(System.getProperty("basedir",System.getProperty("user.dir")))
                .toAbsolutePath();
        Path sql=dir.resolve("../deploy/registration/review-only/"
                +"V10__approved_clinic_registration_writer.sql").normalize();
        assertThat(Files.isRegularFile(sql)).isTrue();
        try(Connection c=ownerDataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                ScriptUtils.executeSqlScript(c,
                        new EncodedResource(new FileSystemResource(sql.toFile())),
                        false,false,ScriptUtils.DEFAULT_COMMENT_PREFIX,
                        ScriptUtils.EOF_STATEMENT_SEPARATOR,
                        ScriptUtils.DEFAULT_BLOCK_COMMENT_START_DELIMITER,
                        ScriptUtils.DEFAULT_BLOCK_COMMENT_END_DELIMITER);
                RegistrationDatabaseProvisioner.installProofKey(c,1,PROOF);
                c.commit();
            } catch(Exception error) {
                c.rollback();
                throw error;
            }
        }
    }

    record Staff(UUID tenant,UUID branch,UUID otherBranch,UUID member,
                 UUID session,String tenantCode,String username,String password) {}

    private Staff staff(boolean permission) {
        String suffix=UUID.randomUUID().toString().substring(0,8);
        UUID tenant=UUID.randomUUID(),facility=UUID.randomUUID(),
                branch=UUID.randomUUID(),other=UUID.randomUUID(),
                user=UUID.randomUUID(),member=UUID.randomUUID(),
                role=UUID.randomUUID(),session=UUID.randomUUID();
        String tenantCode="rhc_"+suffix;
        String username="staff_"+suffix;
        String password="SyntheticCandidate_HTTP_Password_123!";
        admin.update("INSERT INTO tenant(id,code,display_name) VALUES (?,?,?)",
                tenant,tenantCode,"Fictional clinic");
        admin.update("INSERT INTO facility(id,tenant_id,code,display_name,facility_kind) VALUES (?,?,?,'Fictional facility','CLINIC')",
                facility,tenant,"f_"+suffix);
        admin.update("INSERT INTO branch(id,tenant_id,facility_id,code,display_name) VALUES (?,?,?,?,'Fictional reception')",
                branch,tenant,facility,"b_"+suffix);
        admin.update("INSERT INTO branch(id,tenant_id,facility_id,code,display_name) VALUES (?,?,?,?,'Unassigned branch')",
                other,tenant,facility,"o_"+suffix);
        admin.update("INSERT INTO app_user(id,username,password_hash,must_change_password) VALUES (?,?,?,false)",
                user,username,passwords.encode(password));
        admin.update("INSERT INTO user_tenant_membership(id,user_id,tenant_id) VALUES (?,?,?)",
                member,user,tenant);
        admin.update("INSERT INTO auth_role(id,tenant_id,code,display_name) VALUES (?,?,?,?)",
                role,tenant,"RECEPTION","Fictional receptionist");
        if(permission) admin.update(
                "INSERT INTO auth_role_permission(tenant_id,role_id,permission_code) VALUES (?,?,'PATIENT_REGISTER')",
                tenant,role);
        admin.update("INSERT INTO auth_membership_role(tenant_id,membership_id,role_id) VALUES (?,?,?)",
                tenant,member,role);
        admin.update("INSERT INTO org_membership_branch_scope(tenant_id,membership_id,branch_id) VALUES (?,?,?)",
                tenant,member,branch);
        // Only disposable fixture: the real PATIENT_REGISTRATION module stays unreleased.
        admin.update("UPDATE module_registry SET implemented=true,released=true WHERE code='PATIENT_REGISTRATION'");
        admin.update("INSERT INTO tenant_module_state(tenant_id,module_code,entitled,activated,clinically_authorized) VALUES (?,'PATIENT_REGISTRATION',true,true,true)",
                tenant);
        return new Staff(tenant,branch,other,member,session,tenantCode,username,password);
    }

    private HttpResponse<String> post(String path,String body,String token,String key)
            throws Exception {
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path))
                .header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if(token!=null) builder.header("Authorization","Bearer "+token);
        if(key!=null) builder.header("Idempotency-Key",key);
        return http.send(builder.build(),HttpResponse.BodyHandlers.ofString());
    }
    private String login(Staff staff) throws Exception {
        var response=post("/api/v1/auth/login",
                "{\"tenantCode\":\""+staff.tenantCode()+"\",\"username\":\""+
                        staff.username()+"\",\"password\":\""+staff.password()+"\"}",
                null,null);
        assertThat(response.statusCode()).isEqualTo(200);
        Matcher matcher=Pattern.compile("\"accessToken\"\\s*:\\s*\"([A-Za-z0-9_-]{43})\"")
                .matcher(response.body());
        assertThat(matcher.find()).isTrue();
        String token=matcher.group(1);
        // Verify the login has created a usable DB-backed bearer session.
        // A success response from /auth/login alone is insufficient proof.
        var me=http.send(HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:"+port+"/api/v1/auth/me"))
                .header("Authorization","Bearer "+token).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(me.statusCode())
                .as("Just-issued staff bearer token must authorize /auth/me")
                .isEqualTo(200);
        return token;
    }
    private String path(UUID branch) {
        return "/api/v1/branches/"+branch+"/patient-registrations";
    }
    private static String idem() {
        return UUID.randomUUID().toString().replace("-","")
                +UUID.randomUUID().toString().replace("-","");
    }
    private static String intake(String name) {
        return "{\"displayName\":\""+name+"\",\"birthDatePrecision\":\"UNKNOWN\","
                +"\"dateOfBirth\":null}";
    }
    private int count(UUID tenant,String table) {
        return admin.queryForObject("SELECT count(*) FROM public."+table+
                " WHERE tenant_id=?",Integer.class,tenant);
    }

    @Test
    void realHttpCommitAndSameKeyRetryPersistExactlyOneRegistration() throws Exception {
        Staff staff=staff(true);
        String token=login(staff),key=idem();
        String payload=intake("Fictional Candidate Wire One");
        var created=post(path(staff.branch()),payload,token,key);
        assertThat(created.statusCode()).isEqualTo(201);
        assertThat(created.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(created.body()).contains("\"replayed\":false");
        assertThat(created.body()).doesNotContain("Fictional Candidate Wire One",
                "accessToken","password","contact");
        var replay=post(path(staff.branch()),payload,token,key);
        assertThat(replay.statusCode()).isEqualTo(200);
        assertThat(replay.body()).contains("\"replayed\":true");
        for(String table:new String[]{"patient","patient_identifier","patient_registration",
                "patient_registration_idempotency","patient_registration_write_audit"}) {
            assertThat(count(staff.tenant(),table)).isEqualTo(1);
        }
        assertThat(admin.queryForObject("SELECT display_name FROM patient WHERE tenant_id=?",
                String.class,staff.tenant())).isEqualTo("Fictional Candidate Wire One");
    }

    @Test
    void connectedReceptionFrontendCompletesRealHttpRegistrationAndSqlCommit()
            throws Exception {
        Staff staff=staff(true);
        // Only this disposable test staff role receives the separately gated
        // read permission. Runtime grants and negative fixtures stay unchanged.
        admin.update("""
                INSERT INTO auth_role_permission(tenant_id,role_id,permission_code)
                SELECT tenant_id,role_id,'PATIENT_REGISTRATION_READ'
                  FROM auth_membership_role
                 WHERE tenant_id=? AND membership_id=?
                """,staff.tenant(),staff.member());
        Path base=Path.of(System.getProperty("basedir",
                System.getProperty("user.dir"))).toAbsolutePath();
        Path script=base.resolve("../frontend/test/integration/"
                +"connected-reception-wire.mjs").normalize();
        assertThat(Files.isRegularFile(script))
                .as("Connected Reception wire test must be present").isTrue();

        var process=new ProcessBuilder("node","--experimental-strip-types",
                script.toString()).redirectErrorStream(true).start();
        try {
            String fixture=String.format(java.util.Locale.ROOT,
                    "{\"origin\":\"http://127.0.0.1:%d/\",\"branchId\":\"%s\","
                    +"\"tenantCode\":\"%s\",\"username\":\"%s\",\"password\":\"%s\"}",
                    port,staff.branch(),staff.tenantCode(),staff.username(),staff.password());
            // The only credentials in this fixture belong to fictional disposable
            // test accounts. Deliver via process stdin, not command-line argv.
            try(var stdin=process.getOutputStream()) {
                stdin.write(fixture.getBytes(StandardCharsets.UTF_8));
            }
            assertThat(process.waitFor(90,java.util.concurrent.TimeUnit.SECONDS))
                    .as("Connected Reception Node acceptance must terminate").isTrue();
            String output=new String(process.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);
            assertThat(process.exitValue()).as("Connected Reception Node exit").isZero();
            assertThat(output).contains("CONNECTED_RECEPTION_WIRE_PASS");
            assertThat(output).contains("REGISTRATION_RELOGIN_READBACK_PASS");
        } finally {
            if(process.isAlive())process.destroyForcibly();
        }

        for(String table:new String[]{"patient","patient_identifier",
                "patient_registration","patient_registration_idempotency",
                "patient_registration_write_audit"}) {
            assertThat(count(staff.tenant(),table))
                    .as("One durable row in "+table).isEqualTo(1);
        }
        assertThat(admin.queryForObject(
                "SELECT display_name FROM patient WHERE tenant_id=?",
                String.class,staff.tenant()))
                .isEqualTo("Fictional Connected Reception Wire");
        assertThat(admin.queryForObject(
                "SELECT count(*) FROM auth_session WHERE tenant_id=? AND revoked_at IS NOT NULL",
                Integer.class,staff.tenant())).isEqualTo(2);
        assertThat(admin.queryForObject(
                "SELECT count(*) FROM patient_registration_access_audit WHERE tenant_id=?",
                Integer.class,staff.tenant())).isEqualTo(1);
    }

    @Test
    void duplicateOrChangedRequestDoesNotCreateSecondPatient() throws Exception {
        Staff staff=staff(true);
        String token=login(staff),key=idem(),name="Fictional Candidate Wire Duplicate";
        assertThat(post(path(staff.branch()),intake(name),token,key).statusCode())
                .isEqualTo(201);
        var altered=post(path(staff.branch()),intake("Fictional Changed"),token,key);
        assertThat(altered.statusCode()).isEqualTo(409);
        assertThat(altered.body()).contains("IDEMPOTENCY_CONFLICT");
        var duplicate=post(path(staff.branch()),intake(name),token,idem());
        assertThat(duplicate.statusCode()).isEqualTo(409);
        assertThat(duplicate.body()).contains("DUPLICATE_REVIEW_REQUIRED");
        assertThat(count(staff.tenant(),"patient")).isEqualTo(1);
    }

    @Test
    void missingAuthenticationPermissionAndOtherBranchAreDenied() throws Exception {
        Staff staff=staff(true),unprivileged=staff(false);
        String valid=login(staff),limited=login(unprivileged);
        assertThat(post(path(staff.branch()),intake("Fictional Anonymous"),null,idem())
                .statusCode()).isEqualTo(401);
        assertThat(post(path(unprivileged.branch()),intake("Fictional No Permission"),
                limited,idem()).statusCode()).isEqualTo(403);
        assertThat(post(path(staff.otherBranch()),intake("Fictional Wrong Branch"),
                valid,idem()).statusCode()).isEqualTo(403);
        assertThat(count(staff.tenant(),"patient")).isZero();
        assertThat(count(unprivileged.tenant(),"patient")).isZero();
    }

    @Test
    void revokeSessionAndModuleAfterLoginDeniesSqlWrite() throws Exception {
        Staff staff=staff(true);
        String token=login(staff);
        admin.update("UPDATE auth_session SET revoked_at=now() WHERE tenant_id=?",
                staff.tenant());
        assertThat(post(path(staff.branch()),intake("Fictional Revoked Session"),
                token,idem()).statusCode()).isEqualTo(401);
        String fresh=login(staff);
        admin.update("UPDATE tenant_module_state SET activated=false WHERE tenant_id=?",
                staff.tenant());
        assertThat(post(path(staff.branch()),intake("Fictional Disabled Module"),
                fresh,idem()).statusCode()).isEqualTo(403);
        assertThat(count(staff.tenant(),"patient")).isZero();
    }

    @Test
    void oversizedBodyRejectedBeforeRegistrationAndAudit() throws Exception {
        Staff staff=staff(true);
        String token=login(staff);
        String payload="{\"displayName\":\""+"x".repeat(5000)+
                "\",\"birthDatePrecision\":\"UNKNOWN\",\"dateOfBirth\":null}";
        var result=post(path(staff.branch()),payload,token,idem());
        assertThat(result.statusCode()).isEqualTo(413);
        assertThat(result.body()).contains("REGISTRATION_PAYLOAD_TOO_LARGE");
        assertThat(count(staff.tenant(),"patient")).isZero();
    }
}
