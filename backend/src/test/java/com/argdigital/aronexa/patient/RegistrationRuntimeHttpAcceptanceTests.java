package com.argdigital.aronexa.patient;

import com.argdigital.aronexa.auth.TenantAuthorization;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.util.UUID;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Main-source HTTP boundary with a test-only writer and fictional disposable PG17.
 * No production migration, installation key storage or activation is approved here.
 */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties={"server.address=127.0.0.1",
                "aronexa.patient.registration.reviewed-activation=true"})
@ActiveProfiles({"test","registration-runtime-candidate"})
@Import(RegistrationRuntimeHttpAcceptanceTests.DisposableWriter.class)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class RegistrationRuntimeHttpAcceptanceTests {
    private static final byte[] PROOF=new byte[32], IDEM=new byte[32];
    static {
        var random=new SecureRandom();
        random.nextBytes(PROOF);
        random.nextBytes(IDEM);
    }

    @Container @ServiceConnection
    static PostgreSQLContainer postgres=new PostgreSQLContainer("postgres:17-alpine")
            .withInitScript("db/test/role-bootstrap.sql")
            .withDatabaseName("aronexa_registration_runtime_http")
            .withUsername("aronexa_registration_runtime_http")
            .withPassword("ephemeral-test-owner-only")
            .withLabel("com.argdigital.aronexa.qa.scope","registration-runtime-synthetic-only");

    @Autowired JdbcTemplate admin;
    @Autowired PasswordEncoder passwords;
    @Autowired javax.sql.DataSource ownerDataSource;
    @Autowired @Qualifier("patientJdbcTemplate") JdbcTemplate patientJdbc;
    @Autowired org.springframework.context.ApplicationContext context;
    @Autowired org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping handlers;
    @Value("${local.server.port}") int port;
    private final HttpClient http=HttpClient.newHttpClient();

    @TestConfiguration(proxyBeanMethods=false)
    @Profile("test & registration-runtime-candidate & !dev & !prod")
    static class DisposableWriter {
        @Bean RegistrationWriteExecutor disposableRegistrationWriter(
                TenantAuthorization authorization,PatientSqlScope scope,
                @Qualifier("patientJdbcTemplate") JdbcTemplate jdbc,
                @Qualifier("patientTransactionManager") PlatformTransactionManager manager) {
            return new RegistrationWriteExecutor(authorization,scope,jdbc,manager,
                    IDEM,PROOF,1,java.time.Clock.systemUTC());
        }
    }

    @BeforeAll
    void installReviewCandidateOnlyInThisDisposableDatabase() throws Exception {
        Path backend=Path.of(System.getProperty("basedir",System.getProperty("user.dir"))).toAbsolutePath();
        Path sql=backend.resolve("../deploy/registration/review-only/"
                +"V10__approved_clinic_registration_writer.sql").normalize();
        assertThat(Files.isRegularFile(sql)).isTrue();
        try(Connection c=ownerDataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                ScriptUtils.executeSqlScript(c,new EncodedResource(new FileSystemResource(sql)),
                        false,false,ScriptUtils.DEFAULT_COMMENT_PREFIX,ScriptUtils.EOF_STATEMENT_SEPARATOR,
                        ScriptUtils.DEFAULT_BLOCK_COMMENT_START_DELIMITER,ScriptUtils.DEFAULT_BLOCK_COMMENT_END_DELIMITER);
                RegistrationDatabaseProvisioner.installProofKey(c,1,PROOF);
                c.commit();
            } catch(Exception error) {
                c.rollback();
                throw error;
            }
        }
    }

    record Staff(UUID tenant,UUID branch,UUID otherBranch,UUID member,UUID user,
                 String tenantCode,String username,String password) {}

    private Staff staff(boolean permitted) {
        String suffix=UUID.randomUUID().toString().substring(0,8);
        UUID tenant=UUID.randomUUID(),facility=UUID.randomUUID(),branch=UUID.randomUUID(),
                other=UUID.randomUUID(),user=UUID.randomUUID(),member=UUID.randomUUID(),role=UUID.randomUUID();
        String tenantCode="rrh_"+suffix,username="staff_"+suffix,password="Fictional_Runtime_HTTP_123!";
        admin.update("INSERT INTO tenant(id,code,display_name) VALUES (?,?,'Fictional clinic')",tenant,tenantCode);
        admin.update("INSERT INTO facility(id,tenant_id,code,display_name,facility_kind) VALUES (?,?,?,'Fictional facility','CLINIC')",
                facility,tenant,"f_"+suffix);
        admin.update("INSERT INTO branch(id,tenant_id,facility_id,code,display_name) VALUES (?,?,?,?,'Fictional reception')",
                branch,tenant,facility,"b_"+suffix);
        admin.update("INSERT INTO branch(id,tenant_id,facility_id,code,display_name) VALUES (?,?,?,?,'Unassigned fictional branch')",
                other,tenant,facility,"o_"+suffix);
        admin.update("INSERT INTO app_user(id,username,password_hash,must_change_password) VALUES (?,?,?,false)",
                user,username,passwords.encode(password));
        admin.update("INSERT INTO user_tenant_membership(id,user_id,tenant_id) VALUES (?,?,?)",member,user,tenant);
        admin.update("INSERT INTO auth_role(id,tenant_id,code,display_name) VALUES (?,?,?,'Fictional receptionist')",role,tenant,"RECEPTION");
        if(permitted)admin.update("INSERT INTO auth_role_permission(tenant_id,role_id,permission_code) VALUES (?,?,'PATIENT_REGISTER')",tenant,role);
        admin.update("INSERT INTO auth_membership_role(tenant_id,membership_id,role_id) VALUES (?,?,?)",tenant,member,role);
        admin.update("INSERT INTO org_membership_branch_scope(tenant_id,membership_id,branch_id) VALUES (?,?,?)",tenant,member,branch);
        admin.update("UPDATE module_registry SET implemented=true,released=true WHERE code='PATIENT_REGISTRATION'");
        admin.update("INSERT INTO tenant_module_state(tenant_id,module_code,entitled,activated,clinically_authorized) VALUES (?,'PATIENT_REGISTRATION',true,true,true)",tenant);
        return new Staff(tenant,branch,other,member,user,tenantCode,username,password);
    }

    private HttpResponse<String> post(String path,String body,String token,String key) throws Exception {
        return post(path,HttpRequest.BodyPublishers.ofString(body),token,key);
    }
    private HttpResponse<String> post(String path,HttpRequest.BodyPublisher body,String token,String key) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path))
                .header("Content-Type","application/json").POST(body);
        if(token!=null)request.header("Authorization","Bearer "+token);
        if(key!=null)request.header("Idempotency-Key",key);
        return http.send(request.build(),HttpResponse.BodyHandlers.ofString());
    }
    private String login(Staff staff) throws Exception {
        var response=post("/api/v1/auth/login","{\"tenantCode\":\""+staff.tenantCode()+
                "\",\"username\":\""+staff.username()+"\",\"password\":\""+staff.password()+"\"}",null,null);
        assertThat(response.statusCode()).isEqualTo(200);
        var matcher=Pattern.compile("\"accessToken\"\\s*:\\s*\"([A-Za-z0-9_-]{43})\"").matcher(response.body());
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }
    private static String path(UUID branch) {return "/api/v1/branches/"+branch+"/patient-registrations";}
    private static String idem() {return UUID.randomUUID().toString().replace("-","")+UUID.randomUUID().toString().replace("-","");}
    private static String intake(String name) {return "{\"displayName\":\""+name+"\",\"birthDatePrecision\":\"UNKNOWN\",\"dateOfBirth\":null}";}
    private int count(UUID tenant,String table) {
        return admin.queryForObject("SELECT count(*) FROM public."+table+" WHERE tenant_id=?",Integer.class,tenant);
    }

    @Test
    void mainSourceHttpWriterCommitsAndReplaysOneDurableRegistration() throws Exception {
        Staff staff=staff(true);
        String token=login(staff),key=idem(),body=intake("Fictional Runtime Durable One");
        var created=post(path(staff.branch()),body,token,key);
        assertThat(created.statusCode()).isEqualTo(201);
        assertThat(created.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(created.body()).contains("\"replayed\":false");
        var replay=post(path(staff.branch()),body,token,key);
        assertThat(replay.statusCode()).isEqualTo(200);
        assertThat(replay.body().replace("true","false")).isEqualTo(created.body());
        for(String table:new String[]{"patient","patient_identifier","patient_registration",
                "patient_registration_idempotency","patient_registration_write_audit"}) {
            assertThat(count(staff.tenant(),table)).as("one committed row in "+table).isOne();
        }
        assertThat(created.body()).doesNotContain("Fictional Runtime Durable One","accessToken","password","secret");
    }


    @Test
    void onlyTheMainSourceControllerHandlesRegistrationPosts() {
        var mapped=handlers.getHandlerMethods().entrySet().stream()
                .filter(entry->entry.getKey().getMethodsCondition().getMethods()
                    .contains(org.springframework.web.bind.annotation.RequestMethod.POST))
                .filter(entry->entry.getKey().getPatternValues().contains(
                    "/api/v1/branches/{branchId}/patient-registrations"))
                .map(entry->entry.getValue().getBeanType().getName()).toList();
        assertThat(mapped).containsExactly("com.argdigital.aronexa.patient.RegistrationHttpController");
        assertThat(context.getEnvironment().getActiveProfiles())
                .doesNotContain("registration-candidate","c2-synthetic","dev","prod");
    }

    @Test
    void existingSqlRoleHasNoDirectIdentityWriteOrSigningKeyRead() {
        assertThat(patientJdbc.queryForObject("SELECT current_user",String.class))
                .isEqualTo("aronexa_patient_runtime");
        assertThat(patientJdbc.queryForObject("""
                SELECT r.rolsuper OR r.rolbypassrls OR r.rolcreatedb OR r.rolcreaterole
                FROM pg_catalog.pg_roles r WHERE r.rolname=current_user
                """,Boolean.class)).isFalse();
        for(String table:new String[]{"patient","patient_identifier","patient_registration"}) {
            assertThat(patientJdbc.queryForObject(
                    "SELECT has_table_privilege(current_user,?,'INSERT')",Boolean.class,"public."+table))
                    .as("patient runtime cannot insert directly into "+table).isFalse();
        }
        assertThat(patientJdbc.queryForObject("""
                SELECT has_table_privilege(current_user,
                'public.patient_registration_proof_key','SELECT')
                """,Boolean.class)).isFalse();
        assertThatThrownBy(()->patientJdbc.queryForList("SELECT secret FROM patient_registration_proof_key"))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    @Test
    void changedIdempotencyPayloadAndDuplicateIdentityReturnConflictWithoutExtraRows() throws Exception {
        Staff staff=staff(true);
        String token=login(staff),key=idem(),body=intake("Fictional Runtime Duplicate");
        assertThat(post(path(staff.branch()),body,token,key).statusCode()).isEqualTo(201);
        var changed=post(path(staff.branch()),intake("Fictional Runtime Changed"),token,key);
        assertThat(changed.statusCode()).isEqualTo(409);
        assertThat(changed.body()).contains("IDEMPOTENCY_CONFLICT");
        var duplicate=post(path(staff.branch()),body,token,idem());
        assertThat(duplicate.statusCode()).isEqualTo(409);
        assertThat(duplicate.body()).contains("DUPLICATE_REVIEW_REQUIRED");
        assertThat(count(staff.tenant(),"patient")).isOne();
        assertThat(count(staff.tenant(),"patient_registration_write_audit")).isOne();
    }

    @Test
    void anonymousWrongRoleOtherBranchAndOtherTenantCannotWrite() throws Exception {
        Staff staff=staff(true),limited=staff(false),other=staff(true);
        String token=login(staff),limitedToken=login(limited);
        assertThat(post(path(staff.branch()),intake("Fictional Anonymous"),null,idem()).statusCode()).isEqualTo(401);
        assertThat(post(path(limited.branch()),intake("Fictional Wrong Role"),limitedToken,idem()).statusCode()).isEqualTo(403);
        assertThat(post(path(staff.otherBranch()),intake("Fictional Wrong Branch"),token,idem()).statusCode()).isEqualTo(403);
        assertThat(post(path(other.branch()),intake("Fictional Other Tenant"),token,idem()).statusCode()).isEqualTo(403);
        for(Staff fixture:new Staff[]{staff,limited,other}) {
            assertThat(count(fixture.tenant(),"patient")).isZero();
            assertThat(count(fixture.tenant(),"patient_registration_write_audit")).isZero();
        }
    }

    @Test
    void liveSessionModulePermissionAndBranchRevocationsFailClosed() throws Exception {
        Staff fixture=staff(true);
        String token=login(fixture);
        admin.update("UPDATE auth_session SET revoked_at=now() WHERE tenant_id=?",fixture.tenant());
        assertThat(post(path(fixture.branch()),intake("Fictional Revoked Session"),token,idem()).statusCode()).isEqualTo(401);
        String fresh=login(fixture);
        admin.update("UPDATE tenant_module_state SET activated=false WHERE tenant_id=?",fixture.tenant());
        assertThat(post(path(fixture.branch()),intake("Fictional Disabled Module"),fresh,idem()).statusCode()).isEqualTo(403);
        admin.update("UPDATE tenant_module_state SET activated=true WHERE tenant_id=?",fixture.tenant());
        admin.update("DELETE FROM auth_role_permission WHERE tenant_id=? AND permission_code='PATIENT_REGISTER'",fixture.tenant());
        assertThat(post(path(fixture.branch()),intake("Fictional Revoked Permission"),fresh,idem()).statusCode()).isEqualTo(403);
        admin.update("""
                INSERT INTO auth_role_permission(tenant_id,role_id,permission_code)
                SELECT tenant_id,role_id,'PATIENT_REGISTER' FROM auth_membership_role
                WHERE tenant_id=? AND membership_id=?
                """,fixture.tenant(),fixture.member());
        admin.update("DELETE FROM org_membership_branch_scope WHERE tenant_id=? AND membership_id=?",fixture.tenant(),fixture.member());
        assertThat(post(path(fixture.branch()),intake("Fictional Revoked Branch"),fresh,idem()).statusCode()).isEqualTo(403);
        assertThat(count(fixture.tenant(),"patient")).isZero();
        assertThat(count(fixture.tenant(),"patient_registration_write_audit")).isZero();
    }

    @Test
    void invalidJsonMissingIdempotencyAndInvalidIntakeNeverReachPersistence() throws Exception {
        Staff fixture=staff(true);
        String token=login(fixture);
        for(String invalid:new String[]{"{","null","{}",
                "{\"displayName\":\"Fictional Invalid Precision\",\"birthDatePrecision\":\"INVALID\"}",
                "{\"displayName\":\"Fictional Inconsistent Date\",\"birthDatePrecision\":\"UNKNOWN\",\"dateOfBirth\":\"2000-01-01\"}"}) {
            var denied=post(path(fixture.branch()),invalid,token,idem());
            assertThat(denied.statusCode()).as("malformed/invalid intake").isEqualTo(400);
            assertThat(denied.headers().firstValue("Cache-Control")).contains("no-store");
            assertThat(denied.body()).contains("INVALID_REGISTRATION_REQUEST");
            assertThat(denied.body()).doesNotContain("Fictional","Exception","SELECT","secret");
        }
        assertThat(post(path(fixture.branch()),intake("Fictional Missing Key"),token,null).statusCode()).isEqualTo(400);
        assertThat(post(path(fixture.branch()),intake("Fictional Invalid Key"),token,"short").statusCode()).isEqualTo(400);
        assertThat(count(fixture.tenant(),"patient")).isZero();
        assertThat(count(fixture.tenant(),"patient_registration_write_audit")).isZero();
    }

    @Test
    void exactStreamedBodyCapRejectsBothLengthAndChunkedOverflowWithoutWrites() throws Exception {
        Staff fixture=staff(true);
        String token=login(fixture),valid=intake("Fictional Runtime Stream Boundary");
        String overflow=valid+" ".repeat(4097-valid.getBytes(StandardCharsets.UTF_8).length);
        var known=post(path(fixture.branch()),overflow,token,idem());
        assertThat(known.statusCode()).isEqualTo(413);
        assertThat(known.body()).contains("REGISTRATION_PAYLOAD_TOO_LARGE");
        var chunked=post(path(fixture.branch()),HttpRequest.BodyPublishers.ofInputStream(
                ()->new ByteArrayInputStream(overflow.getBytes(StandardCharsets.UTF_8))),token,idem());
        assertThat(chunked.statusCode()).isEqualTo(413);
        assertThat(chunked.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(count(fixture.tenant(),"patient")).isZero();
        assertThat(count(fixture.tenant(),"patient_registration_write_audit")).isZero();
        String exact=valid+" ".repeat(4096-valid.getBytes(StandardCharsets.UTF_8).length);
        var accepted=post(path(fixture.branch()),HttpRequest.BodyPublishers.ofInputStream(
                ()->new ByteArrayInputStream(exact.getBytes(StandardCharsets.UTF_8))),token,idem());
        assertThat(accepted.statusCode()).isEqualTo(201);
        assertThat(count(fixture.tenant(),"patient")).isOne();
    }

    @Test
    void authenticatedMalformedAttemptsExhaustActorBudgetBeforeJsonParsing() throws Exception {
        Staff fixture=staff(true);
        String token=login(fixture);
        for(int attempt=0;attempt<12;attempt++) {
            assertThat(post(path(fixture.branch()),"{",token,idem()).statusCode())
                    .as("malformed authenticated attempt "+attempt).isEqualTo(400);
        }
        var limited=post(path(fixture.branch()),"{",token,idem());
        assertThat(limited.statusCode()).isEqualTo(429);
        assertThat(limited.headers().firstValue("Retry-After")).contains("60");
        assertThat(limited.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(limited.body()).contains("REGISTRATION_RATE_LIMITED");
        assertThat(post(path(fixture.branch()),intake("Fictional Limited Attempt"),token,idem()).statusCode()).isEqualTo(429);
        assertThat(count(fixture.tenant(),"patient")).isZero();
        assertThat(count(fixture.tenant(),"patient_registration_write_audit")).isZero();
    }



    private String anotherReceptionTokenInTheSameTenant(Staff fixture) throws Exception {
        String suffix=UUID.randomUUID().toString().substring(0,8);
        UUID user=UUID.randomUUID(),member=UUID.randomUUID();
        String username="staff_"+suffix,password="Fictional_Runtime_HTTP_123!";
        admin.update("INSERT INTO app_user(id,username,password_hash,must_change_password) VALUES (?,?,?,false)",
                user,username,passwords.encode(password));
        admin.update("INSERT INTO user_tenant_membership(id,user_id,tenant_id) VALUES (?,?,?)",member,user,fixture.tenant());
        admin.update("""
                INSERT INTO auth_membership_role(tenant_id,membership_id,role_id)
                SELECT tenant_id,?,role_id FROM auth_membership_role
                WHERE tenant_id=? AND membership_id=?
                """,member,fixture.tenant(),fixture.member());
        admin.update("INSERT INTO org_membership_branch_scope(tenant_id,membership_id,branch_id) VALUES (?,?,?)",
                fixture.tenant(),member,fixture.branch());
        return login(new Staff(fixture.tenant(),fixture.branch(),fixture.otherBranch(),member,user,
                fixture.tenantCode(),username,password));
    }

    @Test
    void tenantBudgetCannotBeBypassedBySwitchingAuthenticatedStaffMembers() throws Exception {
        Staff fixture=staff(true);
        for(int actor=0;actor<10;actor++) {
            String token=actor==0?login(fixture):anotherReceptionTokenInTheSameTenant(fixture);
            for(int attempt=0;attempt<12;attempt++) {
                assertThat(post(path(fixture.branch()),"{",token,idem()).statusCode())
                        .as("tenant attempt "+(actor*12+attempt+1)).isEqualTo(400);
            }
        }
        String eleventh=anotherReceptionTokenInTheSameTenant(fixture);
        var limited=post(path(fixture.branch()),"{",eleventh,idem());
        assertThat(limited.statusCode()).isEqualTo(429);
        assertThat(limited.body()).contains("REGISTRATION_RATE_LIMITED");
        assertThat(count(fixture.tenant(),"patient")).isZero();
        assertThat(count(fixture.tenant(),"patient_registration_write_audit")).isZero();
    }

    @Test
    void forcedPasswordChangeAfterLoginDeniesWriteWithoutPartialRows() throws Exception {
        Staff fixture=staff(true);
        String token=login(fixture);
        admin.update("UPDATE app_user SET must_change_password=true WHERE id=?",fixture.user());
        assertThat(post(path(fixture.branch()),intake("Fictional Forced Password Change"),token,idem())
                .statusCode()).isEqualTo(403);
        assertThat(count(fixture.tenant(),"patient")).isZero();
        assertThat(count(fixture.tenant(),"patient_registration_write_audit")).isZero();
    }



    @Test
    void encodedRouteCharactersStillCannotBypassTheStreamedBodyLimit() throws Exception {
        Staff fixture=staff(true);
        String token=login(fixture),branch=fixture.branch().toString();
        String encodedBranch="/api/v1/branches/%"+Integer.toHexString(branch.charAt(0))
                +branch.substring(1)+"/patient-registrations";
        String encodedResource="/api/v1/branches/"+branch+"/%70atient-registrations";
        String encodedPrefix="/api/v1/%62ranches/"+branch+"/patient-registrations";
        String encodedVersion="/api/%761/branches/"+branch+"/patient-registrations";
        String encodedResourceMiddle="/api/v1/branches/"+branch+"/patient-%72egistrations";
        String valid=intake("Fictional Encoded Route Overflow"),overflow=valid+" ".repeat(4097-valid.length());
        for(String route:java.util.List.of(encodedBranch,encodedResource,encodedPrefix,encodedVersion,encodedResourceMiddle)) {
            var denied=post(route,HttpRequest.BodyPublishers.ofInputStream(
                    ()->new ByteArrayInputStream(overflow.getBytes(StandardCharsets.UTF_8))),token,idem());
            assertThat(denied.statusCode()).as("streamed cap follows decoded MVC route").isEqualTo(413);
        }
        assertThat(count(fixture.tenant(),"patient")).isZero();
        assertThat(count(fixture.tenant(),"patient_registration_write_audit")).isZero();
    }

    @Test
    void concurrentSameKeyHttpRequestsCommitOnlyOneIdentityAndAudit() throws Exception {
        Staff fixture=staff(true);
        String token=login(fixture),key=idem(),body=intake("Fictional Concurrent Runtime Registration");
        var start=new java.util.concurrent.CountDownLatch(1);
        try(var workers=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<HttpResponse<String>> request=()->{
                assertThat(start.await(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                return post(path(fixture.branch()),body,token,key);
            };
            var first=workers.submit(request);
            var second=workers.submit(request);
            start.countDown();
            var a=first.get(30,java.util.concurrent.TimeUnit.SECONDS);
            var b=second.get(30,java.util.concurrent.TimeUnit.SECONDS);
            assertThat(java.util.List.of(a.statusCode(),b.statusCode())).containsExactlyInAnyOrder(201,200);
            assertThat(a.body().replace("true","false")).isEqualTo(b.body().replace("true","false"));
        }
        for(String table:new String[]{"patient","patient_identifier","patient_registration",
                "patient_registration_idempotency","patient_registration_write_audit"}) {
            assertThat(count(fixture.tenant(),table)).as("one concurrent committed row in "+table).isOne();
        }
    }

}
