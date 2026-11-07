package com.argdigital.aronexa.patient;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * P01-T07-C2. Real MVC filters, opaque bearer token lookup, restricted patient
 * SQL pool and test-only V10 in fresh disposable Testcontainers PostgreSQL.
 * No production migration, patient data, web endpoint deployment or DB config.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ActiveProfiles({"test", "c2-synthetic"})
@TestPropertySource(properties =
        "aronexa.c2.synthetic-hmac-base64=QUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUE=")
class SyntheticRegistrationHttpAcceptanceTests {
    @Container @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine")
            .withInitScript("db/test/role-bootstrap.sql")
            .withDatabaseName("aronexa_c2_http_isolated")
            .withUsername("aronexa_c2_http_isolated")
            .withPassword("ephemeral-only-migrator");

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate admin;
    @Autowired DataSource dataSource;

    record Staff(UUID tenant, UUID branch, UUID membership, UUID user, String token) {}

    @BeforeAll void installProposedMigrationOnlyInEphemeralDatabase() {
        Flyway isolated = Flyway.configure().dataSource(dataSource)
                .locations("classpath:db/migration", "classpath:db/proposed").load();
        assertThat(isolated.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(isolated.info().current().getVersion().getVersion()).isEqualTo("10");
        assertThat(isolated.validateWithResult().validationSuccessful).isTrue();
    }

    private static String suffix() { return UUID.randomUUID().toString().substring(0,8); }
    private static String idem() { return "C2SyntheticKey_"+UUID.randomUUID().toString().replace("-",""); }
    private static String intake(String name) {
        return "{\"displayName\":\""+name+"\",\"birthDatePrecision\":\"UNKNOWN\",\"dateOfBirth\":null}";
    }
    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    private Staff staff(boolean withPermission, boolean forcePasswordChange) {
        UUID tenant=UUID.randomUUID(), f=UUID.randomUUID(), branch=UUID.randomUUID();
        UUID user=UUID.randomUUID(), membership=UUID.randomUUID(), role=UUID.randomUUID();
        admin.update("INSERT INTO tenant(id,code,display_name) VALUES (?,?,?)",
                tenant,"t_"+suffix(),"Synthetic customer");
        admin.update("INSERT INTO facility(id,tenant_id,code,display_name,facility_kind) VALUES (?,?,?,'Demo facility','CLINIC')",
                f,tenant,"f_"+suffix());
        admin.update("INSERT INTO branch(id,tenant_id,facility_id,code,display_name) VALUES (?,?,?,?,'Demo branch')",
                branch,tenant,f,"b_"+suffix());
        admin.update("INSERT INTO app_user(id,username,password_hash,must_change_password) VALUES (?,?,?,?)",
                user,"test_"+suffix(),"synthetic-not-used-by-auth",forcePasswordChange);
        admin.update("INSERT INTO user_tenant_membership(id,user_id,tenant_id) VALUES (?,?,?)",
                membership,user,tenant);
        admin.update("INSERT INTO org_membership_branch_scope(tenant_id,membership_id,branch_id) VALUES (?,?,?)",
                tenant,membership,branch);
        admin.update("INSERT INTO auth_role(id,tenant_id,code,display_name) VALUES (?,?,'RECEPTION','Receptionist')",
                role,tenant);
        admin.update("INSERT INTO auth_membership_role(tenant_id,membership_id,role_id) VALUES (?,?,?)",
                tenant,membership,role);
        if (withPermission) {
            admin.update("INSERT INTO auth_role_permission(tenant_id,role_id,permission_code) VALUES (?,?,'PATIENT_REGISTER')",
                    tenant,role);
        }
        // Administrative *synthetic fixture* provisioning. V10 does not enable anything.
        admin.update("UPDATE module_registry SET implemented=true,released=true WHERE code='PATIENT_REGISTRATION'");
        admin.update("INSERT INTO tenant_module_state(tenant_id,module_code,entitled,activated,clinically_authorized) VALUES (?,'PATIENT_REGISTRATION',true,true,true)",
                tenant);
        byte[] raw=new byte[32];
        new SecureRandom().nextBytes(raw);
        String token=Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        admin.update("INSERT INTO auth_session(id,membership_id,tenant_id,token_sha256,expires_at) VALUES (?,?,?,?,now()+interval '30 minutes')",
                UUID.randomUUID(),membership,tenant,digest(token));
        return new Staff(tenant,branch,membership,user,token);
    }
    private String route(UUID branch) {
        return "/api/v1/branches/"+branch+"/patient-registrations";
    }
    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request(
            Staff user, UUID branch, String key, String body) {
        return post(route(branch)).header("Authorization","Bearer "+user.token())
                .header("Idempotency-Key",key)
                .contentType(MediaType.APPLICATION_JSON).content(body);
    }

    @Test void noBearerMalformedBearerAndUnsupportedMethodAreDenied() throws Exception {
        Staff f=staff(true,false);
        mvc.perform(post(route(f.branch())).contentType(MediaType.APPLICATION_JSON)
                        .content(intake("Synthetic Anonymous")))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(route(f.branch())).header("Authorization","Bearer invalid")
                        .contentType(MediaType.APPLICATION_JSON).content(intake("Synthetic Malformed")))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(route(f.branch())).header("Authorization","Bearer "+f.token()))
                .andExpect(status().isForbidden());
        assertThat(admin.queryForObject("SELECT count(*) FROM patient WHERE tenant_id=?",
                Integer.class,f.tenant())).isZero();
    }

    @Test void authorizedPostCreatesOneRecordAndReplaysWithoutReturningNameOrContact() throws Exception {
        Staff f=staff(true,false);
        String key=idem(), body=intake("Synthetic Http Success");
        String created=mvc.perform(request(f,f.branch(),key,body))
                .andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control",org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.mrn").isString())
                .andExpect(jsonPath("$.replayed").value(false))
                .andReturn().getResponse().getContentAsString();
        assertThat(created).doesNotContain("Synthetic Http Success","phone","contact");
        mvc.perform(request(f,f.branch(),key,body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(true))
                .andExpect(jsonPath("$.mrn").value(
                        new com.fasterxml.jackson.databind.ObjectMapper().readTree(created).get("mrn").asText()));
        assertThat(admin.queryForObject("SELECT count(*) FROM patient WHERE tenant_id=?",
                Integer.class,f.tenant())).isEqualTo(1);
        assertThat(admin.queryForObject("SELECT count(*) FROM patient_registration_write_audit WHERE tenant_id=? AND actor_membership_id=?",
                Integer.class,f.tenant(),f.membership())).isEqualTo(1);
    }

    @Test void alteredReplayAndNameDuplicateBothReturnSafeConflicts() throws Exception {
        Staff f=staff(true,false); String key=idem();
        mvc.perform(request(f,f.branch(),key,intake("Synthetic Http Duplicate")))
                .andExpect(status().isCreated());
        mvc.perform(request(f,f.branch(),key,intake("Synthetic Http Changed")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
        mvc.perform(request(f,f.branch(),idem(),intake("Synthetic Http Duplicate")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_REVIEW_REQUIRED"));
        assertThat(admin.queryForObject("SELECT count(*) FROM patient WHERE tenant_id=?",
                Integer.class,f.tenant())).isEqualTo(1);
    }

    @Test void missingPermissionWrongBranchAndDisabledModuleReturn403WithoutWrites() throws Exception {
        Staff missing=staff(false,false);
        mvc.perform(request(missing,missing.branch(),idem(),intake("Synthetic No Permission")))
                .andExpect(status().isForbidden());
        Staff valid=staff(true,false);
        mvc.perform(request(valid,missing.branch(),idem(),intake("Synthetic Wrong Branch")))
                .andExpect(status().isForbidden());
        admin.update("UPDATE tenant_module_state SET activated=false WHERE tenant_id=? AND module_code='PATIENT_REGISTRATION'",
                valid.tenant());
        mvc.perform(request(valid,valid.branch(),idem(),intake("Synthetic Disabled Module")))
                .andExpect(status().isForbidden());
        assertThat(admin.queryForObject("SELECT count(*) FROM patient WHERE tenant_id IN (?,?)",
                Integer.class,missing.tenant(),valid.tenant())).isZero();
    }

    @Test void passwordChangeRequiredAndRevokedSessionAreDeniedByAuthenticationFilter() throws Exception {
        Staff forced=staff(true,true);
        mvc.perform(request(forced,forced.branch(),idem(),intake("Synthetic Forced Password")))
                .andExpect(status().isForbidden());
        Staff valid=staff(true,false);
        admin.update("UPDATE auth_session SET revoked_at=now() WHERE membership_id=?",valid.membership());
        mvc.perform(request(valid,valid.branch(),idem(),intake("Synthetic Revoked Token")))
                .andExpect(status().isUnauthorized());
    }

    @Test void missingKeyInvalidBirthDateAndForgedTenantHeaderFailClosed() throws Exception {
        Staff f=staff(true,false), other=staff(true,false);
        mvc.perform(post(route(f.branch())).header("Authorization","Bearer "+f.token())
                        .contentType(MediaType.APPLICATION_JSON).content(intake("Synthetic No Idempotency")))
                .andExpect(status().isBadRequest());
        mvc.perform(request(f,f.branch(),idem(),
                    "{\"displayName\":\"Synthetic Future\",\"birthDatePrecision\":\"EXACT\",\"dateOfBirth\":\"2099-01-01\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(request(f,other.branch(),idem(),intake("Synthetic Forged Scope"))
                    .header("X-Tenant-Id",other.tenant().toString())
                    .header("X-Branch-Id",other.branch().toString()))
                .andExpect(status().isForbidden());
        assertThat(admin.queryForObject("SELECT count(*) FROM patient WHERE tenant_id IN (?,?)",
                Integer.class,f.tenant(),other.tenant())).isZero();
    }
}
