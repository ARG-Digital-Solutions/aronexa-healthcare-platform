package com.argdigital.aronexa.opd;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PROD-INT-01: actual HTTP -> production controller -> production service ->
 * dedicated nonowner patient SQL/RLS, with V1-V9 only on disposable PG17.
 *
 * Unlike the older OPD fixture this has no injected test-only HTTP controller,
 * clinical write SQL or auto-enabled module in production. Each fixture
 * activates its own tenant modules explicitly IN THIS DISPOSABLE DB ONLY.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"server.address=127.0.0.1","server.error.include-message=never"})
@ActiveProfiles("test")
@Testcontainers
class OpdIntakeProductionHttpAcceptanceTests {
    @Container @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine")
            .withInitScript("db/test/role-bootstrap.sql")
            .withDatabaseName("aronexa_prodint01_disposable_only")
            .withUsername("aronexa_prodint01_disposable_only")
            .withPassword("synthetic-test-admin-only");

    @Autowired JdbcTemplate admin;
    @Autowired PasswordEncoder passwords;
    @Value("${local.server.port}") int port;
    private final HttpClient http = HttpClient.newHttpClient();

    record Fixture(UUID tenant,UUID branch,UUID otherBranch,UUID member,
                   UUID registration,UUID patient,String code,String user,
                   String password) {}

    private Fixture seed(boolean appointmentBook,boolean registrationRead,
                         boolean opdEnabled,boolean patientModuleEnabled,
                         boolean mustChangePassword) {
        String suffix=UUID.randomUUID().toString().substring(0,8);
        UUID tenant=UUID.randomUUID(),facility=UUID.randomUUID(),
             branch=UUID.randomUUID(),other=UUID.randomUUID(),
             user=UUID.randomUUID(),member=UUID.randomUUID(),
             role=UUID.randomUUID(),patient=UUID.randomUUID(),
             registration=UUID.randomUUID();
        String code="prodint_"+suffix,username="reception_"+suffix;
        String password="SyntheticIntake-Only123!";
        // APPOINTMENT_BOOK is intentionally not an automatically provisioned
        // production role entitlement. Here it exists only in disposable PG17.
        admin.update("""
                INSERT INTO public.auth_permission(code,description)
                VALUES ('APPOINTMENT_BOOK','Disposable OPD intake eligibility test')
                ON CONFLICT(code) DO NOTHING
                """);
        admin.update("INSERT INTO tenant(id,code,display_name) VALUES (?,?,?)",
                tenant,code,"Fictional OPD intake clinic");
        admin.update("""
                INSERT INTO facility(id,tenant_id,code,display_name,facility_kind)
                VALUES (?,?,?,'Fictional facility','CLINIC')
                """,facility,tenant,"f_"+suffix);
        admin.update("""
                INSERT INTO branch(id,tenant_id,facility_id,code,display_name)
                VALUES(?,?,?,?,'Fictional branch')
                """,branch,tenant,facility,"b_"+suffix);
        admin.update("""
                INSERT INTO branch(id,tenant_id,facility_id,code,display_name)
                VALUES(?,?,?,?,'Foreign branch')
                """,other,tenant,facility,"o_"+suffix);
        admin.update("""
                INSERT INTO app_user(id,username,password_hash,must_change_password)
                VALUES (?,?,?,?)
                """,user,username,passwords.encode(password),mustChangePassword);
        admin.update("INSERT INTO user_tenant_membership(id,user_id,tenant_id) VALUES (?,?,?)",
                member,user,tenant);
        admin.update("INSERT INTO auth_role(id,tenant_id,code,display_name) VALUES (?,?,?,?)",
                role,tenant,"RECEPTION","Fictional receptionist");
        if(appointmentBook)admin.update("""
                INSERT INTO auth_role_permission(tenant_id,role_id,permission_code)
                VALUES (?,?,'APPOINTMENT_BOOK')
                """,tenant,role);
        if(registrationRead)admin.update("""
                INSERT INTO auth_role_permission(tenant_id,role_id,permission_code)
                VALUES (?,?,'PATIENT_REGISTRATION_READ')
                """,tenant,role);
        admin.update("INSERT INTO auth_membership_role(tenant_id,membership_id,role_id) VALUES (?,?,?)",
                tenant,member,role);
        admin.update("""
                INSERT INTO org_membership_branch_scope(tenant_id,membership_id,branch_id)
                VALUES (?,?,?)
                """,tenant,member,branch);
        admin.update("INSERT INTO patient(id,tenant_id,display_name) VALUES (?,?,?)",
                patient,tenant,"Fictional Subject Only");
        admin.update("""
                INSERT INTO patient_registration(
                    id,tenant_id,patient_id,branch_id,
                    registration_source,registered_by_membership_id)
                VALUES (?,?,?,?,'RECEPTION',?)
                """,registration,tenant,patient,branch,member);

        // Fixture owner alone can toggle test flags; this code is not part
        // of startup/provisioning, clinic SQL or any application endpoint.
        if(opdEnabled||patientModuleEnabled) {
            admin.update("""
                    UPDATE module_registry SET implemented=TRUE,released=TRUE
                    WHERE code IN ('OPD','PATIENT_REGISTRATION')
                    """);
        }
        if(opdEnabled)admin.update("""
                INSERT INTO tenant_module_state
                    (tenant_id,module_code,entitled,activated,clinically_authorized)
                VALUES (?,'OPD',TRUE,TRUE,TRUE)
                """,tenant);
        if(patientModuleEnabled)admin.update("""
                INSERT INTO tenant_module_state
                    (tenant_id,module_code,entitled,activated,clinically_authorized)
                VALUES (?,'PATIENT_REGISTRATION',TRUE,TRUE,TRUE)
                """,tenant);
        return new Fixture(tenant,branch,other,member,registration,patient,
                code,username,password);
    }

    private String url(UUID branch,UUID registration) {
        return "/api/v1/branches/"+branch+"/opd/intake/"+registration;
    }
    private HttpResponse<String> get(String path,String token) throws Exception {
        var builder=HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:"+port+path)).GET()
                .header("Accept","application/json");
        if(token!=null)builder.header("Authorization","Bearer "+token);
        return http.send(builder.build(),HttpResponse.BodyHandlers.ofString());
    }
    private HttpResponse<String> post(String path,String token,String body) throws Exception {
        var builder=HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:"+port+path))
                .header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if(token!=null)builder.header("Authorization","Bearer "+token);
        return http.send(builder.build(),HttpResponse.BodyHandlers.ofString());
    }
    private String login(Fixture f) throws Exception {
        String body="{\"tenantCode\":\""+f.code()+"\",\"username\":\""+
                f.user()+"\",\"password\":\""+f.password()+"\"}";
        var result=post("/api/v1/auth/login",null,body);
        assertThat(result.statusCode()).isEqualTo(200);
        var match=Pattern.compile("\"accessToken\"\\s*:\\s*\"([A-Za-z0-9_-]{43})\"")
                .matcher(result.body());
        assertThat(match.find()).isTrue();
        return match.group(1);
    }
    private int reads(Fixture f) {
        return admin.queryForObject("""
                SELECT count(*) FROM patient_registration_access_audit
                WHERE tenant_id=? AND membership_id=? AND registration_id=?
                """,Integer.class,f.tenant(),f.member(),f.registration());
    }

    @Test
    void prodInt02ProductionBookingRouteRemainsDisabledWithoutReviewFlag()
            throws Exception {
        Fixture f=seed(true,true,true,true,false);
        String token=login(f);
        String path="/api/v1/branches/"+f.branch()+"/opd/appointments";
        String booking="{\"registrationId\":\""+f.registration()+"\","
                +"\"doctorMembershipId\":\""+UUID.randomUUID()+"\","
                +"\"visitDate\":\"2026-11-14\",\"slotStart\":\"10:30:00\","
                +"\"requestId\":\""+UUID.randomUUID()+"\"}";
        // Correct auth, role and module are insufficient when independent
        // production activation or restricted SQL writer was not approved.
        assertThat(post(path,token,booking).statusCode()).isEqualTo(403);
        assertThat(post(path+"/"+UUID.randomUUID()+"/check-in",token,
                "{\"expectedRevision\":1,\"requestId\":\""+
                UUID.randomUUID()+"\"}").statusCode()).isEqualTo(403);
        assertThat(reads(f)).isZero();
    }

    @Test
    void validIntakeUsesRealAuthenticatedNonownerPatientReadAndAudit()
            throws Exception {
        Fixture f=seed(true,true,true,true,false);
        String token=login(f);
        var response=get(url(f.branch(),f.registration()),token);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(response.body()).contains(
                f.tenant().toString(),f.branch().toString(),
                f.registration().toString(),f.patient().toString(),
                "REGISTRATION_VERIFIED");
        assertThat(response.body()).doesNotContain(
                "membershipId","sessionId","username","password",
                "Fictional Subject Only","displayName","dateOfBirth","accessToken");
        assertThat(reads(f)).isOne();
        // This production-source intake GET must never unlock writes.
        var write=post("/api/v1/branches/"+f.branch()+"/appointments",
                token,"{}");
        assertThat(write.statusCode()).isEqualTo(403);
    }

    @Test
    void authenticatedWrongTenantBranchAndUnknownRegistrationFailClosed()
            throws Exception {
        Fixture one=seed(true,true,true,true,false);
        Fixture foreign=seed(true,true,true,true,false);
        String token=login(one);
        assertThat(get(url(one.otherBranch(),one.registration()),token)
                .statusCode()).isEqualTo(403);
        assertThat(get(url(one.branch(),UUID.randomUUID()),token)
                .statusCode()).isEqualTo(403);
        assertThat(get(url(foreign.branch(),foreign.registration()),token)
                .statusCode()).isEqualTo(403);
        assertThat(reads(one)).isZero();
        assertThat(reads(foreign)).isZero();
        admin.update("""
                DELETE FROM org_membership_branch_scope
                WHERE tenant_id=? AND membership_id=? AND branch_id=?
                """,one.tenant(),one.member(),one.branch());
        assertThat(get(url(one.branch(),one.registration()),token)
                .statusCode()).isEqualTo(403);
        assertThat(reads(one)).isZero();
    }

    @Test
    void eachPermissionAndBothModulesAreIndependentlyRequired() throws Exception {
        for(int mode=0;mode<4;mode++) {
            Fixture f=switch(mode) {
                case 0 -> seed(false,true,true,true,false);
                case 1 -> seed(true,false,true,true,false);
                case 2 -> seed(true,true,false,true,false);
                default -> seed(true,true,true,false,false);
            };
            assertThat(get(url(f.branch(),f.registration()),login(f))
                    .statusCode()).isEqualTo(403);
            assertThat(reads(f)).isZero();
        }
    }

    @Test
    void anonymousForcedPasswordAndRevokedSessionCannotRead() throws Exception {
        Fixture f=seed(true,true,true,true,false);
        assertThat(get(url(f.branch(),f.registration()),null).statusCode())
                .isEqualTo(401);
        assertThat(get(url(f.branch(),f.registration()),"invalid").statusCode())
                .isEqualTo(401);
        assertThat(reads(f)).isZero();
        String token=login(f);
        assertThat(get(url(f.branch(),f.registration()),token).statusCode())
                .isEqualTo(200);
        assertThat(post("/api/v1/auth/logout",token,"{}").statusCode())
                .isBetween(200,204);
        assertThat(get(url(f.branch(),f.registration()),token).statusCode())
                .isEqualTo(401);
        assertThat(reads(f)).isOne();
        Fixture forced=seed(true,true,true,true,true);
        assertThat(get(url(forced.branch(),forced.registration()),login(forced))
                .statusCode()).isEqualTo(403);
        assertThat(reads(forced)).isZero();
    }
}
