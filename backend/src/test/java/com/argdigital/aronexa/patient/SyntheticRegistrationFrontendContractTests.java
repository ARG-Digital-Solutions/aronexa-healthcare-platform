package com.argdigital.aronexa.patient;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * INT-REG-01: frozen Reception -> protected C2 synthetic HTTP contract tests.
 * Only disposable PostgreSQL, synthetic names and test-resource proposed V10.
 * Never invokes a frontend endpoint or touches persistent Windows databases.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ActiveProfiles({"test", "c2-synthetic"})
@TestPropertySource(properties =
        "aronexa.c2.synthetic-hmac-base64=QUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUE=")
class SyntheticRegistrationFrontendContractTests {
    private static final byte[] EPHEMERAL_PROOF_KEY = new byte[32];
    static { new SecureRandom().nextBytes(EPHEMERAL_PROOF_KEY); }

    @DynamicPropertySource
    static void proofSecret(DynamicPropertyRegistry registry) {
        registry.add("aronexa.c2.synthetic-proof-base64",
                () -> Base64.getEncoder().encodeToString(EPHEMERAL_PROOF_KEY));
    }

    @Container @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine")
            .withInitScript("db/test/role-bootstrap.sql")
            .withDatabaseName("aronexa_int_reg01_disposable")
            .withUsername("aronexa_int_reg01_disposable")
            .withPassword("test-only-ephemeral-migrator");

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate admin;
    @Autowired DataSource dataSource;
    @Autowired JsonMapper mapper;
    private JsonNode contract;

    record Staff(UUID tenant, UUID branch, String token) {}

    @BeforeAll
    void initializeDisposableContractDb() throws Exception {
        try (InputStream in = getClass().getResourceAsStream(
                "/contracts/p01-t07-int-reg01-registration-v1.json")) {
            assertThat(in).as("Contract fixture must exist").isNotNull();
            contract = mapper.readTree(in);
        }
        assertThat(contract.path("schemaVersion").asText()).isEqualTo("1.0.0");
        Flyway isolated = Flyway.configure().dataSource(dataSource)
                .locations("classpath:db/migration", "classpath:db/proposed").load();
        assertThat(isolated.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(isolated.info().current().getVersion().getVersion()).isEqualTo("10");
        assertThat(isolated.validateWithResult().validationSuccessful).isTrue();
        admin.update("INSERT INTO public.patient_registration_proof_key(id,secret) VALUES (1,?)",
                EPHEMERAL_PROOF_KEY);
    }

    private static String shortId() { return UUID.randomUUID().toString().substring(0, 8); }
    private static String key() {
        return "IntReg01_" + UUID.randomUUID().toString().replace("-", "");
    }
    private static String digest(String token) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(token.getBytes(StandardCharsets.UTF_8)));
    }
    private Staff staff(boolean permission) throws Exception {
        UUID tenant=UUID.randomUUID(), facility=UUID.randomUUID(),
                branch=UUID.randomUUID(), user=UUID.randomUUID(),
                membership=UUID.randomUUID(), role=UUID.randomUUID();
        admin.update("INSERT INTO tenant(id,code,display_name) VALUES (?,?,?)",
                tenant,"ir_" + shortId(),"Synthetic contract tenant");
        admin.update("INSERT INTO facility(id,tenant_id,code,display_name,facility_kind) "+
                "VALUES (?,?,?,'Synthetic clinic','CLINIC')",
                facility,tenant,"ir_" + shortId());
        admin.update("INSERT INTO branch(id,tenant_id,facility_id,code,display_name) "+
                "VALUES (?,?,?,?,'Synthetic branch')",
                branch,tenant,facility,"ir_" + shortId());
        admin.update("INSERT INTO app_user(id,username,password_hash,must_change_password) "+
                "VALUES (?,?,?,false)",user,"intreg_" + shortId(),"synthetic-unused");
        admin.update("INSERT INTO user_tenant_membership(id,user_id,tenant_id) VALUES (?,?,?)",
                membership,user,tenant);
        admin.update("INSERT INTO org_membership_branch_scope(tenant_id,membership_id,branch_id) "+
                "VALUES (?,?,?)",tenant,membership,branch);
        admin.update("INSERT INTO auth_role(id,tenant_id,code,display_name) "+
                "VALUES (?,?,'RECEPTION','Receptionist')",role,tenant);
        admin.update("INSERT INTO auth_membership_role(tenant_id,membership_id,role_id) "+
                "VALUES (?,?,?)",tenant,membership,role);
        if (permission) admin.update("INSERT INTO auth_role_permission(tenant_id,role_id,permission_code) "+
                "VALUES (?,?,'PATIENT_REGISTER')",tenant,role);
        admin.update("UPDATE module_registry SET implemented=true,released=true "+
                "WHERE code='PATIENT_REGISTRATION'");
        admin.update("INSERT INTO tenant_module_state"+
                "(tenant_id,module_code,entitled,activated,clinically_authorized) "+
                "VALUES (?,'PATIENT_REGISTRATION',true,true,true)",tenant);
        byte[] raw=new byte[32];
        new SecureRandom().nextBytes(raw);
        String token=Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        admin.update("INSERT INTO auth_session(id,membership_id,tenant_id,token_sha256,expires_at) "+
                "VALUES (?,?,?,?,now()+interval '30 minutes')",
                UUID.randomUUID(),membership,tenant,digest(token));
        return new Staff(tenant,branch,token);
    }
    private String url(UUID branch) {
        return "/api/v1/branches/" + branch + "/patient-registrations";
    }
    /**
     * Test-side translation of the frozen UI payload, NOT a production adapter.
     * It refuses to silently discard nonempty phone input or manufacture branch IDs.
     */
    private String mappedFrontendBody(JsonNode fixture) throws Exception {
        JsonNode front=fixture.path("frontend");
        if (front.isMissingNode() || !front.isObject()) {
            throw new IllegalArgumentException("Missing frontend fixture");
        }
        if (!front.path("phone").asText("").isBlank()) {
            throw new IllegalArgumentException("Unsupported phone field; API activation blocked");
        }
        String name=front.path("name").asText("").strip().replaceAll("\\s+", " ");
        if (name.length() < 2 || name.length() > 100
                || name.codePoints().noneMatch(Character::isLetter)
                || name.codePoints().anyMatch(c -> Character.isISOControl(c) || c == '<' || c == '>')) {
            throw new IllegalArgumentException("Invalid frozen UI patient name");
        }
        String precision=front.path("precision").asText("");
        String dob=front.path("dob").asText("");
        var backend=mapper.createObjectNode();
        backend.put("displayName",name);
        if ("UNKNOWN".equals(precision)) {
            if (!dob.isEmpty()) throw new IllegalArgumentException("Unknown birth date must be empty");
            backend.put("birthDatePrecision","UNKNOWN");
            backend.putNull("dateOfBirth");
        } else if ("FULL".equals(precision)) {
            if (!dob.matches("\\d{4}-\\d{2}-\\d{2}")) {
                throw new IllegalArgumentException("Exact date must be ISO yyyy-MM-dd");
            }
            LocalDate date;
            try { date=LocalDate.parse(dob); }
            catch (DateTimeParseException e) {
                throw new IllegalArgumentException("Invalid calendar date");
            }
            if (date.isAfter(LocalDate.now())) throw new IllegalArgumentException("Future birth date");
            backend.put("birthDatePrecision","EXACT");
            backend.put("dateOfBirth",dob);
        } else {
            throw new IllegalArgumentException("Unknown frontend birth precision");
        }
        return mapper.writeValueAsString(backend);
    }
    private JsonNode fixture(String id) {
        for (JsonNode item : contract.path("fixtures")) {
            if (id.equals(item.path("id").asText())) return item;
        }
        throw new IllegalArgumentException("Unknown synthetic fixture");
    }
    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request(
            Staff s, UUID branch, String idempotency, String body) {
        return post(url(branch)).header("Authorization","Bearer " + s.token())
                .header("Idempotency-Key",idempotency)
                .contentType(MediaType.APPLICATION_JSON).content(body);
    }

    @Test
    void versionedContractDocumentsExplicitFrontendMappingAndNoLiveActivation() throws Exception {
        assertThat(contract.path("restrictions").path("testOnly").asBoolean()).isTrue();
        assertThat(contract.path("restrictions").path("frontendConnected").asBoolean()).isFalse();
        assertThat(contract.path("restrictions").path("productionEnabled").asBoolean()).isFalse();
        JsonNode unknown=fixture("INTREG01-UNKNOWN");
        JsonNode exact=fixture("INTREG01-EXACT");
        assertThat(unknown.path("frontend").path("precision").asText()).isEqualTo("UNKNOWN");
        assertThat(unknown.path("backend").path("birthDatePrecision").asText()).isEqualTo("UNKNOWN");
        assertThat(unknown.path("backend").path("dateOfBirth").isNull()).isTrue();
        assertThat(mapper.readTree(mappedFrontendBody(unknown))).isEqualTo(unknown.path("backend"));
        assertThat(exact.path("frontend").path("precision").asText()).isEqualTo("FULL");
        assertThat(exact.path("backend").path("birthDatePrecision").asText()).isEqualTo("EXACT");
        assertThat(LocalDate.parse(exact.path("backend").path("dateOfBirth").asText()))
                .isEqualTo(LocalDate.of(2001,4,12));
        assertThat(mapper.readTree(mappedFrontendBody(exact))).isEqualTo(exact.path("backend"));
        assertThat(fixture("INTREG01-PHONE-BLOCK").path("expectedContract").asText())
                .isEqualTo("BLOCK_UNSUPPORTED_PHONE");
        assertThat(fixture("INTREG01-PHONE-BLOCK").has("backend")).isFalse();
    }

    @Test
    void unknownPrecisionCreatesSafeReceiptReplaysAndNeverAutoMerges() throws Exception {
        Staff s=staff(true);
        String body=mappedFrontendBody(fixture("INTREG01-UNKNOWN"));
        String idempotency=key();
        var created=mvc.perform(request(s,s.branch(),idempotency,body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.replayed").value(false))
                .andExpect(jsonPath("$.mrn").isString())
                .andReturn().getResponse().getContentAsString();
        assertThat(created).doesNotContain("Synthetic Contract Unknown","phone","token");
        var retry=mvc.perform(request(s,s.branch(),idempotency,body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(true))
                .andReturn().getResponse().getContentAsString();
        assertThat(mapper.readTree(retry).path("patientId").asText())
                .isEqualTo(mapper.readTree(created).path("patientId").asText());
        mvc.perform(request(s,s.branch(),idempotency,
                body.replace("Contract Unknown","Changed Identity")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
        mvc.perform(request(s,s.branch(),key(),body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_REVIEW_REQUIRED"));
        assertThat(admin.queryForObject(
                "SELECT count(*) FROM patient WHERE tenant_id=?",Integer.class,s.tenant()))
                .isEqualTo(1);
    }

    @Test
    void exactBirthDateAndInvalidDateContractAreEnforced() throws Exception {
        Staff s=staff(true);
        mvc.perform(request(s,s.branch(),key(),mappedFrontendBody(fixture("INTREG01-EXACT"))))
                .andExpect(status().isCreated());
        String future="{\"displayName\":\"Synthetic Contract Future\","+
                "\"birthDatePrecision\":\"EXACT\",\"dateOfBirth\":\"2099-01-01\"}";
        mvc.perform(request(s,s.branch(),key(),future))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REGISTRATION_REQUEST"));
        assertThat(admin.queryForObject(
                "SELECT count(*) FROM patient WHERE tenant_id=?",Integer.class,s.tenant()))
                .isEqualTo(1);
    }

    @Test
    void contractDeniesMissingBearerWrongBranchAndMissingRoleWithoutWrites() throws Exception {
        Staff unprivileged=staff(false), authorized=staff(true);
        String body=mappedFrontendBody(fixture("INTREG01-UNKNOWN"));
        mvc.perform(post(url(authorized.branch())).header("Idempotency-Key",key())
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mvc.perform(request(unprivileged,unprivileged.branch(),key(),body))
                .andExpect(status().isForbidden());
        mvc.perform(request(authorized,unprivileged.branch(),key(),body))
                .andExpect(status().isForbidden());
        assertThat(admin.queryForObject(
                "SELECT count(*) FROM patient WHERE tenant_id IN (?,?)",
                Integer.class,unprivileged.tenant(),authorized.tenant())).isZero();
    }

    @Test
    void unsupportedPhoneCannotBeSilentlyInterpretedAsPersistedPatientContact()
            throws Exception {
        Staff s=staff(true);
        JsonNode unsupported=fixture("INTREG01-PHONE-BLOCK");
        assertThat(unsupported.has("backend")).isFalse();
        assertThat(unsupported.path("frontend").path("phone").asText()).isNotEmpty();
        assertThatThrownBy(() -> mappedFrontendBody(unsupported))
                .isInstanceOf(IllegalArgumentException.class);
        // No API request is sent: mapping must block a phone-bearing frontend
        // intake rather than creating a record and silently dropping contact.
        assertThat(admin.queryForObject(
                "SELECT count(*) FROM patient WHERE tenant_id=?",Integer.class,s.tenant()))
                .isZero();
    }
}
