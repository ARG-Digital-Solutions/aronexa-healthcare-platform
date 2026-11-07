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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
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
    // Every disposable test container has a fresh unknown signing key. Nothing
    // usable for impersonation is checked into the repo or Flyway script.
    private static final byte[] EPHEMERAL_PROOF_KEY = new byte[32];
    static { new SecureRandom().nextBytes(EPHEMERAL_PROOF_KEY); }

    @DynamicPropertySource
    static void syntheticProofSecret(DynamicPropertyRegistry registry) {
        registry.add("aronexa.c2.synthetic-proof-base64",
                () -> Base64.getEncoder().encodeToString(EPHEMERAL_PROOF_KEY));
    }
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
        // Synthetic fixture only: no proof key is shipped by Flyway migration.
        admin.update("INSERT INTO public.patient_registration_proof_key(id,secret) VALUES (1,?)",
                EPHEMERAL_PROOF_KEY);
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
        var mrnMatch = java.util.regex.Pattern.compile("PAT-[0-9A-HJKMNP-TV-Z]{12}").matcher(created);
        assertThat(mrnMatch.find()).isTrue();
        mvc.perform(request(f,f.branch(),key,body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(true))
                .andExpect(jsonPath("$.mrn").value(mrnMatch.group()));
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
    @Test void malformedAuthenticatedRequestsConsumeQuotaAndReturnGeneric429() throws Exception {
        Staff f=staff(true,false);
        String badBody="{\"displayName\":";
        for(int i=0;i<12;i++) {
            mvc.perform(request(f,f.branch(),idem(),badBody))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(request(f,f.branch(),idem(),badBody)
                        .header("X-Tenant-Id",UUID.randomUUID().toString())
                        .header("X-Branch-Id",UUID.randomUUID().toString()))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After","60"))
                .andExpect(header().string("Cache-Control",
                        org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.code").value("REGISTRATION_RATE_LIMITED"));
        assertThat(admin.queryForObject("SELECT count(*) FROM patient WHERE tenant_id=?",
                Integer.class,f.tenant())).isZero();
        // A separate authenticated staff identity is not locked out by this actor.
        Staff other=staff(true,false);
        mvc.perform(request(other,other.branch(),idem(),badBody))
                .andExpect(status().isBadRequest());
    }

    @Test void authenticatedMeResponseNeverExposesInternalSessionId() throws Exception {
        Staff f=staff(true,false);
        mvc.perform(get("/api/v1/auth/me")
                        .header("Authorization","Bearer "+f.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").isString())
                .andExpect(jsonPath("$.membershipId").isString())
                .andExpect(jsonPath("$.sessionId").doesNotExist())
                .andExpect(header().string("Cache-Control",
                        org.hamcrest.Matchers.containsString("no-store")));
    }


    /** C12 uses disposable Testcontainers state; no persistent database writes. */
    private void assertNoRegistrationSideEffects(Staff... identities) {
        for (Staff staff : identities) {
            for (String table : new String[]{"patient", "patient_registration",
                    "patient_registration_write_audit", "patient_registration_proof_nonce",
                    "patient_registration_idempotency"}) {
                assertThat(admin.queryForObject("SELECT count(*) FROM public."+table+
                        " WHERE tenant_id=?", Integer.class,staff.tenant()))
                        .as("No denied-request records in "+table).isZero();
            }
        }
    }

    @Test void expiredSessionDisabledAccountAndDisabledMembershipAllRejectRegistration()
            throws Exception {
        Staff expired=staff(true,false), disabled=staff(true,false),
                inactiveMembership=staff(true,false);
        // Schema requires expires_at > issued_at. Move both timestamps
        // into the past to model a valid but naturally expired session.
        admin.update("UPDATE auth_session SET issued_at=now()-interval '2 hours', "+
                "expires_at=now()-interval '1 hour' WHERE membership_id=?",
                expired.membership());
        admin.update("UPDATE app_user SET enabled=false WHERE id=?",disabled.user());
        admin.update("UPDATE user_tenant_membership SET enabled=false WHERE id=?",
                inactiveMembership.membership());

        mvc.perform(request(expired,expired.branch(),idem(),intake("Synthetic Expired Session")))
                .andExpect(status().isUnauthorized());
        mvc.perform(request(disabled,disabled.branch(),idem(),intake("Synthetic Disabled User")))
                .andExpect(status().isUnauthorized());
        mvc.perform(request(inactiveMembership,inactiveMembership.branch(),idem(),
                        intake("Synthetic Disabled Membership")))
                .andExpect(status().isUnauthorized());
        assertNoRegistrationSideEffects(expired,disabled,inactiveMembership);
    }

    @Test void malformedJsonInvalidClinicalFieldsAndUnsupportedContentTypeNeverWrite()
            throws Exception {
        Staff f=staff(true,false);
        String[] badBodies={
                "{",
                "[]",
                "{\"displayName\":null,\"birthDatePrecision\":\"UNKNOWN\",\"dateOfBirth\":null}",
                "{\"displayName\":\"<script>alert(1)</script>\",\"birthDatePrecision\":\"UNKNOWN\",\"dateOfBirth\":null}",
                intake("Synthetic "+"x".repeat(200)),
                "{\"displayName\":\"Synthetic Control\\u0000 Name\",\"birthDatePrecision\":\"UNKNOWN\",\"dateOfBirth\":null}",
                "{\"displayName\":\"Synthetic Invalid Precision\",\"birthDatePrecision\":\"FUTURE\",\"dateOfBirth\":null}",
                "{\"displayName\":\"Synthetic Unknown Date\",\"birthDatePrecision\":\"UNKNOWN\",\"dateOfBirth\":\"2020-01-01\"}"
        };
        for (String body : badBodies) {
            mvc.perform(request(f,f.branch(),idem(),body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REGISTRATION_REQUEST"));
        }
        mvc.perform(post(route(f.branch()))
                        .header("Authorization","Bearer "+f.token())
                        .header("Idempotency-Key",idem())
                        .contentType(MediaType.TEXT_PLAIN)
                        .content(intake("Synthetic Unsupported Media")))
                .andExpect(status().isUnsupportedMediaType());
        assertNoRegistrationSideEffects(f);
    }

    @Test void rejectedIdempotencyKeysCannotReserveNonceOrCreateAudit() throws Exception {
        Staff f=staff(true,false);
        String[] invalid={
                "too-short",
                "x".repeat(129),
                "Synthetic Idempotency With Spaces",
                "Synthetic_Idempotency/Slash",
                "Synthetic_Idempotency?Query"
        };
        for (String key : invalid) {
            mvc.perform(request(f,f.branch(),key,intake("Synthetic Invalid Idempotency")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REGISTRATION_REQUEST"));
        }
        assertNoRegistrationSideEffects(f);
    }

    @Test void forgedTenantHeadersAndSharedIdempotencyKeyCannotCrossTenantBoundary()
            throws Exception {
        Staff a=staff(true,false), b=staff(true,false);
        String sharedKey=idem();
        String aReceipt=mvc.perform(request(a,a.branch(),sharedKey,
                        intake("Synthetic Tenant Bound A"))
                        .header("X-Tenant-Id",b.tenant().toString())
                        .header("X-Branch-Id",b.branch().toString()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.replayed").value(false))
                .andReturn().getResponse().getContentAsString();
        assertThat(aReceipt).doesNotContain(b.tenant().toString(),b.branch().toString());
        assertThat(admin.queryForObject(
                "SELECT count(*) FROM public.patient WHERE tenant_id=?",
                Integer.class,b.tenant())).isZero();
        mvc.perform(request(b,b.branch(),sharedKey,intake("Synthetic Tenant Bound B")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.replayed").value(false));
        assertThat(admin.queryForObject(
                "SELECT count(*) FROM public.patient WHERE tenant_id=?",
                Integer.class,a.tenant())).isEqualTo(1);
        assertThat(admin.queryForObject(
                "SELECT count(*) FROM public.patient WHERE tenant_id=?",
                Integer.class,b.tenant())).isEqualTo(1);
        for (Staff identity : new Staff[]{a,b}) {
            assertThat(admin.queryForObject("""
                    SELECT count(*) FROM public.patient_registration_write_audit
                    WHERE tenant_id=? AND actor_membership_id=?
                    """,Integer.class,identity.tenant(),identity.membership())).isEqualTo(1);
        }
    }

    @Test void untrustedExtraJsonFieldsCannotChoosePatientIdentityOrTenant()
            throws Exception {
        Staff a=staff(true,false), unrelated=staff(true,false);
        UUID injectedPatient=UUID.randomUUID(), forgedSession=UUID.randomUUID();
        String injectedMrn="PAT-AAAAAAAAAAAA";
        String body=String.format(java.util.Locale.ROOT,
                "{\"displayName\":\"Synthetic Mass Assignment\",\"birthDatePrecision\":\"UNKNOWN\","+
                "\"dateOfBirth\":null,\"patientId\":\"%s\",\"tenantId\":\"%s\","+
                "\"branchId\":\"%s\",\"mrn\":\"%s\",\"sessionId\":\"%s\","+
                "\"permission\":\"PATIENT_REGISTER\",\"role\":\"PLATFORM_ADMIN\"}",
                injectedPatient,unrelated.tenant(),unrelated.branch(),injectedMrn,forgedSession);
        var response=mvc.perform(request(a,a.branch(),idem(),body))
                .andReturn().getResponse();
        // Unknown input fields may be explicitly rejected or safely ignored.
        // In either policy the caller must NEVER choose identity or tenant.
        assertThat(response.getStatus()).isIn(201,400);
        assertThat(response.getContentAsString()).doesNotContain(
                injectedPatient.toString(),unrelated.tenant().toString(),
                unrelated.branch().toString(),injectedMrn,forgedSession.toString());
        assertThat(admin.queryForObject("SELECT count(*) FROM public.patient WHERE id=?",
                Integer.class,injectedPatient)).isZero();
        assertThat(admin.queryForObject("SELECT count(*) FROM public.patient WHERE tenant_id=?",
                Integer.class,unrelated.tenant())).isZero();
        assertThat(admin.queryForObject("SELECT count(*) FROM public.patient WHERE tenant_id=?",
                Integer.class,a.tenant())).isEqualTo(response.getStatus()==201?1:0);
    }

    @Test void unsupportedWriteMethodsAndMalformedBearerCannotModifyPatients()
            throws Exception {
        Staff f=staff(true,false);
        mvc.perform(delete(route(f.branch())).header("Authorization","Bearer "+f.token()))
                .andExpect(status().isForbidden());
        mvc.perform(put(route(f.branch())).header("Authorization","Bearer "+f.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(intake("Synthetic Put Attempt")))
                .andExpect(status().isForbidden());
        mvc.perform(post(route(f.branch()))
                        .header("Authorization","Basic YWJjOmRlZg==")
                        .header("Idempotency-Key",idem())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(intake("Synthetic Basic Attempt")))
                .andExpect(status().isUnauthorized());
        assertNoRegistrationSideEffects(f);
    }


}
