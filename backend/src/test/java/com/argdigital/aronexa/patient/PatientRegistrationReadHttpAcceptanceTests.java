package com.argdigital.aronexa.patient;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import java.util.regex.Matcher;
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
 * Real embedded HTTP + scoped, nonowner PostgreSQL patient role. This is an
 * explicitly synthetic Testcontainers acceptance test, not a production clinic.
 * Uses only existing V1-V9 migrations; never installs the test-only V10 writer.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.address=127.0.0.1")
@ActiveProfiles("test")
@Testcontainers
class PatientRegistrationReadHttpAcceptanceTests {
    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine")
            .withInitScript("db/test/role-bootstrap.sql")
            .withDatabaseName("aronexa_registration_read_http_test")
            .withUsername("aronexa_registration_read_http_test")
            .withPassword("ephemeral-only-test-admin");

    @Autowired JdbcTemplate admin;
    @Autowired PasswordEncoder passwords;
    @Value("$" + "{local.server.port}") int port;
    private final HttpClient client = HttpClient.newHttpClient();

    record Fixture(UUID tenant, UUID branch, UUID otherBranch, UUID membership,
                   UUID patient, UUID registration, String tenantCode,
                   String username, String password) {}

    private Fixture seed(boolean readPermission, boolean activateModule,
                         boolean forcedPasswordChange) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        UUID tenant=UUID.randomUUID(), user=UUID.randomUUID(),
             membership=UUID.randomUUID(), role=UUID.randomUUID(),
             facility=UUID.randomUUID(), branch=UUID.randomUUID(),
             otherBranch=UUID.randomUUID(), patient=UUID.randomUUID(),
             registration=UUID.randomUUID();
        String tenantCode="read_"+suffix, username="reception_"+suffix,
               password="SyntheticOnly-ReadTest-123!";
        admin.update("INSERT INTO tenant(id,code,display_name) VALUES (?,?,?)",
                tenant,tenantCode,"Fictional isolated clinic");
        admin.update("""
                INSERT INTO app_user(id,username,password_hash,must_change_password)
                VALUES (?,?,?,?)
                """,user,username,passwords.encode(password),forcedPasswordChange);
        admin.update("INSERT INTO user_tenant_membership(id,user_id,tenant_id) VALUES (?,?,?)",
                membership,user,tenant);
        admin.update("INSERT INTO auth_role(id,tenant_id,code,display_name) VALUES (?,?,?,?)",
                role,tenant,"RECEPTION","Synthetic receptionist");
        if (readPermission) {
            // PATIENT_REGISTRATION_READ was created by immutable Flyway V9.
            admin.update("""
                    INSERT INTO auth_role_permission(tenant_id,role_id,permission_code)
                    VALUES (?,?,'PATIENT_REGISTRATION_READ')
                    """,tenant,role);
        }
        admin.update("INSERT INTO auth_membership_role(tenant_id,membership_id,role_id) VALUES (?,?,?)",
                tenant,membership,role);
        admin.update("""
                INSERT INTO facility(id,tenant_id,code,display_name,facility_kind)
                VALUES (?,?,?,'Synthetic facility','CLINIC')
                """,facility,tenant,"f_"+suffix);
        admin.update("""
                INSERT INTO branch(id,tenant_id,facility_id,code,display_name)
                VALUES (?,?,?,?,'Synthetic branch')
                """,branch,tenant,facility,"b_"+suffix);
        admin.update("""
                INSERT INTO branch(id,tenant_id,facility_id,code,display_name)
                VALUES (?,?,?,?,'Other synthetic branch')
                """,otherBranch,tenant,facility,"o_"+suffix);
        admin.update("""
                INSERT INTO org_membership_branch_scope(tenant_id,membership_id,branch_id)
                VALUES (?,?,?)
                """,tenant,membership,branch);
        admin.update("""
                INSERT INTO patient(id,tenant_id,display_name)
                VALUES (?,?,'Fictional Reference Patient')
                """,patient,tenant);
        admin.update("""
                INSERT INTO patient_registration
                    (id,tenant_id,patient_id,branch_id,registration_source,registered_by_membership_id)
                VALUES (?,?,?,?,'RECEPTION',?)
                """,registration,tenant,patient,branch,membership);
        if (activateModule) {
            // Explicit migration-owner TEST FIXTURE only; production module remains unreleased.
            admin.update("""
                    UPDATE module_registry SET implemented=TRUE,released=TRUE
                    WHERE code='PATIENT_REGISTRATION'
                    """);
            admin.update("""
                    INSERT INTO tenant_module_state
                        (tenant_id,module_code,entitled,activated,clinically_authorized)
                    VALUES (?,'PATIENT_REGISTRATION',TRUE,TRUE,TRUE)
                    """,tenant);
        }
        return new Fixture(tenant,branch,otherBranch,membership,patient,registration,
                           tenantCode,username,password);
    }

    private String uriPath(Fixture f, UUID branch, UUID registration) {
        return "/api/v1/branches/"+branch+"/patient-registrations/"+registration;
    }
    private HttpResponse<String> get(String path,String bearer) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path))
                .GET();
        if(bearer!=null) builder.header("Authorization","Bearer "+bearer);
        return client.send(builder.build(),HttpResponse.BodyHandlers.ofString());
    }
    private HttpResponse<String> post(String path,String json,String bearer) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path))
                .header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json));
        if(bearer!=null) builder.header("Authorization","Bearer "+bearer);
        return client.send(builder.build(),HttpResponse.BodyHandlers.ofString());
    }
    private String login(Fixture f) throws Exception {
        var response=post("/api/v1/auth/login",
                "{\"tenantCode\":\""+f.tenantCode()+"\",\"username\":\""+
                f.username()+"\",\"password\":\""+f.password()+"\"}",null);
        assertThat(response.statusCode()).isEqualTo(200);
        Matcher match=Pattern.compile("\"accessToken\"\\s*:\\s*\"([A-Za-z0-9_-]{43})\"")
                .matcher(response.body());
        assertThat(match.find()).isTrue();
        return match.group(1);
    }
    private int auditCount(Fixture f) {
        return admin.queryForObject("""
                SELECT count(*) FROM patient_registration_access_audit
                WHERE tenant_id=? AND registration_id=? AND membership_id=?
                """,Integer.class,f.tenant(),f.registration(),f.membership());
    }

    @Test
    void authorizedReadIsAuditedAndNeverReturnsDemographicsOrMembership() throws Exception {
        Fixture f=seed(true,true,false);
        String bearer=login(f);
        var allowed=get(uriPath(f,f.branch(),f.registration()),bearer);
        assertThat(allowed.statusCode()).isEqualTo(200);
        assertThat(allowed.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(allowed.body()).contains(f.tenant().toString(),f.branch().toString(),
                f.registration().toString(),f.patient().toString());
        assertThat(allowed.body()).doesNotContain("Fictional Reference Patient",
                "accessToken","password","membershipId","displayName","dateOfBirth");
        assertThat(auditCount(f)).isEqualTo(1);

        var foreignBranch=get(uriPath(f,f.otherBranch(),f.registration()),bearer);
        var missing=get(uriPath(f,f.branch(),UUID.randomUUID()),bearer);
        assertThat(foreignBranch.statusCode()).isEqualTo(404);
        assertThat(missing.statusCode()).isEqualTo(404);
        assertThat(foreignBranch.body()).isEqualTo(missing.body());
        assertThat(auditCount(f)).isEqualTo(1);

        // Revoking branch assignment takes effect on the NEXT read.
        admin.update("""
                DELETE FROM org_membership_branch_scope
                WHERE tenant_id=? AND membership_id=? AND branch_id=?
                """,f.tenant(),f.membership(),f.branch());
        assertThat(get(uriPath(f,f.branch(),f.registration()),bearer).statusCode())
                .isEqualTo(404);
        assertThat(auditCount(f)).isEqualTo(1);
    }

    @Test
    void anonymousAndMalformedAuthenticationAreRejectedWithoutAudit() throws Exception {
        Fixture f=seed(true,true,false);
        String path=uriPath(f,f.branch(),f.registration());
        assertThat(get(path,null).statusCode()).isEqualTo(401);
        assertThat(get(path,"invalid").statusCode()).isEqualTo(401);
        assertThat(auditCount(f)).isZero();
    }

    @Test
    void missingPermissionNeverLeaksRegistrationEvenWithValidSession() throws Exception {
        Fixture f=seed(false,true,false);
        var denied=get(uriPath(f,f.branch(),f.registration()),login(f));
        assertThat(denied.statusCode()).isEqualTo(403);
        assertThat(denied.body()).doesNotContain("Fictional Reference Patient");
        assertThat(auditCount(f)).isZero();
    }

    @Test
    void unreleasedModuleCannotBeBypassedByBearerOrReadPermission() throws Exception {
        Fixture f=seed(true,false,false);
        var denied=get(uriPath(f,f.branch(),f.registration()),login(f));
        assertThat(denied.statusCode()).isEqualTo(403);
        assertThat(auditCount(f)).isZero();
    }

    @Test
    void forcedPasswordChangeRejectsRegistrationReadAndProductionWriteStaysClosed()
            throws Exception {
        Fixture f=seed(true,true,true);
        String bearer=login(f);
        assertThat(get(uriPath(f,f.branch(),f.registration()),bearer).statusCode())
                .isEqualTo(403);
        assertThat(auditCount(f)).isZero();
        var blocked=post("/api/v1/branches/"+f.branch()+"/patient-registrations",
                "{\"displayName\":\"Fictional\"}",bearer);
        assertThat(blocked.statusCode()).isEqualTo(403);
    }

    @Test
    void revokedTokenCannotReadAgain() throws Exception {
        Fixture f=seed(true,true,false);
        String token=login(f);
        assertThat(get(uriPath(f,f.branch(),f.registration()),token).statusCode())
                .isEqualTo(200);
        assertThat(post("/api/v1/auth/logout","{}",token).statusCode()).isEqualTo(204);
        assertThat(get(uriPath(f,f.branch(),f.registration()),token).statusCode())
                .isEqualTo(401);
        assertThat(auditCount(f)).isEqualTo(1);
    }
}
