package com.argdigital.aronexa.appointment;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two separate Surefire JVM executions: phase A writes and exits; phase B
 * starts a NEW JVM and reopens the same fictional consultation via HTTP.
 * A single PowerShell parent owns one automatically removed Docker container.
 * No Testcontainers static lifecycle, prod endpoint or shared database access.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"server.address=127.0.0.1", "server.error.include-message=never"})
@ActiveProfiles({"test", "appointment-candidate"})
@ContextConfiguration(initializers = Pilot05DisposableDatabaseGuard.class)
@Import({Pilot05DisposableDatabaseGuard.VerifiedDisposableConnection.class,
         AppointmentCandidateHttpAcceptanceTests.CandidateController.class,
         AppointmentCandidateHttpAcceptanceTests.DailyBillingController.class})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Pilot05SeparateJvmHttpTests {
    private static final String NOTE = "SYNTHETIC PILOT05 restart consultation";
    private static final String PASSWORD = "DisposablePilot05-Only123!";
    private static final ZoneId CLINIC_ZONE = ZoneId.of("Asia/Kolkata");
    private static final Pattern TOKEN = Pattern.compile(
            "\"accessToken\"\\s*:\\s*\"([A-Za-z0-9_-]{43})\"");

    @Autowired JdbcTemplate admin;
    @Autowired DataSource ownerDataSource;
    @Autowired PasswordEncoder passwords;
    @Value("$" + "{local.server.port}") int port;
    final HttpClient client = HttpClient.newHttpClient();

    private String suffix() {
        return Pilot05DisposableDatabaseGuard.runId().toString().substring(0, 8);
    }
    private String tenantCode() { return "pilot05_" + suffix(); }
    private String receptionUsername() { return "pilot05_reception_" + suffix(); }
    private String doctorUsername() { return "pilot05_doctor_" + suffix(); }

    @BeforeAll
    void stageIsolatedSqlOnlyInFirstJvm() throws Exception {
        if (!"A".equals(Pilot05DisposableDatabaseGuard.env("ARONEXA_PILOT05_PHASE"))) {
            return;
        }
        assertThat(admin.queryForObject("""
                SELECT phase FROM qa_guard.pilot05_run_marker WHERE run_id=?
                """, String.class, Pilot05DisposableDatabaseGuard.runId()))
                .isEqualTo("READY");
        // Exactly the existing R1-04..R1-08 synthetic appointment SQL.
        try (Connection c = ownerDataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                for (String file : new String[]{
                        "db/test/appointment-persistence-proof.sql",
                        "db/test/appointment-candidate-r104.sql",
                        "db/test/appointment-candidate-r105.sql",
                        "db/test/appointment-candidate-r106.sql",
                        "db/test/appointment-candidate-r107.sql",
                        "db/test/appointment-candidate-r108.sql"
                }) {
                    ScriptUtils.executeSqlScript(c,
                            new EncodedResource(new ClassPathResource(file)),
                            false, false, ScriptUtils.DEFAULT_COMMENT_PREFIX,
                            ScriptUtils.EOF_STATEMENT_SEPARATOR,
                            ScriptUtils.DEFAULT_BLOCK_COMMENT_START_DELIMITER,
                            ScriptUtils.DEFAULT_BLOCK_COMMENT_END_DELIMITER);
                }
                c.commit();
            } catch (Exception failure) {
                c.rollback();
                throw failure;
            }
        }
        admin.update("""
                UPDATE module_registry SET implemented=true,released=true
                WHERE code IN ('OPD','PATIENT_REGISTRATION')
                """);
    }

    private HttpResponse<String> call(String method, String path, String token,
                                       String json) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(
                "http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(20))
                .header("Accept", "application/json");
        if (token != null) builder.header("Authorization", "Bearer " + token);
        if (method.equals("POST")) {
            builder.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
        } else builder.GET();
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String login(String username) throws Exception {
        String json = "{\"tenantCode\":\"" + tenantCode()
                + "\",\"username\":\"" + username
                + "\",\"password\":\"" + PASSWORD + "\"}";
        var response = call("POST", "/api/v1/auth/login", null, json);
        assertThat(response.statusCode()).isEqualTo(200);
        var matcher = TOKEN.matcher(response.body());
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }

    private UUID extract(String json, String key) {
        var pattern = Pattern.compile("\"" + Pattern.quote(key)
                + "\"\\s*:\\s*\"([0-9a-f-]{36})\"", Pattern.CASE_INSENSITIVE);
        var found = pattern.matcher(json);
        assertThat(found.find()).as("Expected server-issued " + key).isTrue();
        return UUID.fromString(found.group(1));
    }

    private record Fixture(UUID tenant, UUID branch, UUID patient,
                           UUID registration, UUID doctor) {}

    private Fixture seed() {
        String part = suffix();
        UUID tenant = UUID.randomUUID(), facility = UUID.randomUUID();
        UUID branch = UUID.randomUUID(), patient = UUID.randomUUID();
        UUID registration = UUID.randomUUID();
        UUID receptionist = UUID.randomUUID(), rUser = UUID.randomUUID();
        UUID doctor = UUID.randomUUID(), dUser = UUID.randomUUID();
        UUID receptionRole = UUID.randomUUID(), doctorRole = UUID.randomUUID();
        admin.update("INSERT INTO tenant(id,code,display_name) VALUES (?,?,?)",
                tenant, tenantCode(), "FICTIONAL pilot05 clinic");
        admin.update("""
                INSERT INTO facility(id,tenant_id,code,display_name,facility_kind)
                VALUES(?,?,?,'Fictional facility','CLINIC')
                """, facility, tenant, "fac_" + part);
        admin.update("""
                INSERT INTO branch(id,tenant_id,facility_id,code,display_name)
                VALUES(?,?,?,?,'Fictional branch')
                """, branch, tenant, facility, "branch_" + part);
        for (Object[] row : new Object[][]{
                {rUser, receptionUsername()}, {dUser, doctorUsername()}
        }) {
            admin.update("""
                    INSERT INTO app_user(id,username,password_hash,must_change_password)
                    VALUES(?,?,?,false)
                    """, row[0], row[1], passwords.encode(PASSWORD));
        }
        for (Object[] row : new Object[][]{
                {receptionist, rUser}, {doctor, dUser}
        }) {
            admin.update("""
                    INSERT INTO user_tenant_membership(id,user_id,tenant_id)
                    VALUES(?,?,?)
                    """, row[0], row[1], tenant);
            admin.update("""
                    INSERT INTO org_membership_branch_scope(tenant_id,membership_id,branch_id)
                    VALUES(?,?,?)
                    """, tenant, row[0], branch);
        }
        for (Object[] row : new Object[][]{
                {receptionRole, "RECEPTION"}, {doctorRole, "DOCTOR"}
        }) {
            admin.update("""
                    INSERT INTO auth_role(id,tenant_id,code,display_name)
                    VALUES(?,?,?,'Fictional role')
                    """, row[0], tenant, row[1]);
        }
        for (Object[] row : new Object[][]{
                {receptionist, receptionRole}, {doctor, doctorRole}
        }) {
            admin.update("""
                    INSERT INTO auth_membership_role(tenant_id,membership_id,role_id)
                    VALUES(?,?,?)
                    """, tenant, row[0], row[1]);
        }
        for (String permission : new String[]{
                "APPOINTMENT_BOOK", "APPOINTMENT_READ", "APPOINTMENT_CHECK_IN",
                "PATIENT_REGISTRATION_READ"
        }) {
            admin.update("""
                    INSERT INTO auth_role_permission(tenant_id,role_id,permission_code)
                    VALUES(?,?,?)
                    """, tenant, receptionRole, permission);
        }
        admin.update("""
                INSERT INTO auth_role_permission(tenant_id,role_id,permission_code)
                VALUES(?,?,'OPD_DRAFT_WRITE')
                """, tenant, doctorRole);
        admin.update("""
                INSERT INTO tenant_module_state
                  (tenant_id,module_code,entitled,activated,clinically_authorized)
                VALUES(?,'OPD',true,true,true),
                      (?,'PATIENT_REGISTRATION',true,true,true)
                """, tenant,tenant);
        admin.update("""
                INSERT INTO patient(id,tenant_id,display_name)
                VALUES(?,?,'FICTIONAL RESTART RECORD')
                """, patient, tenant);
        // The real appointment-candidate booking contract rejects registrations
        // lacking a patient_identifier MRN. Mirror the existing R1-04 fixture;
        // never weaken registeredPatient() or bypass its authorization checks.
        admin.update("""
                INSERT INTO patient_identifier
                    (id,tenant_id,patient_id,identifier_type,identifier_value)
                VALUES(?,?,?,'MRN',?)
                """, UUID.randomUUID(), tenant, patient,
                "PILOT05-FICTIONAL-" + part.toUpperCase() + "-001");
        assertThat(admin.queryForObject("""
                SELECT count(*) FROM patient_identifier
                WHERE tenant_id=? AND patient_id=? AND identifier_type='MRN'
                """, Integer.class, tenant, patient)).isOne();
        admin.update("""
                INSERT INTO patient_registration
                (id,tenant_id,patient_id,branch_id,registration_source,
                 registered_by_membership_id)
                VALUES(?,?,?,?,'RECEPTION',?)
                """, registration, tenant, patient, branch, receptionist);
        return new Fixture(tenant, branch, patient, registration, doctor);
    }

    @Test
    void phaseAWritesCheckedInConsultationAndStopsJvm() throws Exception {
        assertThat(Pilot05DisposableDatabaseGuard.env("ARONEXA_PILOT05_PHASE"))
                .isEqualTo("A");
        Fixture f = seed();
        String reception = login(receptionUsername());
        String base = "/api/v1/branches/" + f.branch() + "/appointments";
        String payload = "{\"requestId\":\"" + UUID.randomUUID()
                + "\",\"registrationId\":\"" + f.registration()
                + "\",\"doctorMembershipId\":\"" + f.doctor()
                + "\",\"visitDate\":\"" + LocalDate.now(CLINIC_ZONE)
                + "\",\"slotStart\":\"14:30\"}";
        var booked = call("POST", base, reception, payload);
        assertThat(booked.statusCode()).isEqualTo(201);
        UUID appointment = extract(booked.body(), "appointmentId");
        var checked = call("POST", base + "/" + appointment + "/check-in",
                reception, "{\"expectedRevision\":1}");
        assertThat(checked.statusCode()).isEqualTo(200);
        assertThat(checked.body()).contains("\"stage\":\"CHECKED_IN\"");
        assertThat(extract(checked.body(), "patientId")).isEqualTo(f.patient());
        // PILOT-08 RC01 — real server-authenticated registration and queue
        // read before shutdown, not merely privileged owner SQL existence.
        var registrationRead=call("GET", "/api/v1/branches/"+f.branch()
                +"/patient-registrations/"+f.registration(),reception,null);
        assertThat(registrationRead.statusCode()).isEqualTo(200);
        assertThat(registrationRead.body()).contains(
                f.registration().toString(),f.patient().toString());
        assertThat(registrationRead.body()).doesNotContain(
                "FICTIONAL RESTART RECORD","displayName","accessToken");
        var beforeRestartQueue=call("GET",base+"?visitDate="
                +LocalDate.now(CLINIC_ZONE),reception,null);
        assertThat(beforeRestartQueue.statusCode()).isEqualTo(200);
        assertThat(beforeRestartQueue.body()).contains(
                appointment.toString(),f.registration().toString(),
                "\"stage\":\"CHECKED_IN\"");
        assertThat(call("POST", "/api/v1/auth/logout", reception, "{}")
                .statusCode()).isBetween(200, 204);
        assertThat(call("GET", base + "/" + appointment, reception, null)
                .statusCode()).isEqualTo(401);

        String doctor = login(doctorUsername());
        var context = call("GET", base + "/" + appointment + "/consultation-context",
                doctor, null);
        assertThat(context.statusCode()).isEqualTo(200);
        assertThat(extract(context.body(), "patientId")).isEqualTo(f.patient());
        assertThat(extract(context.body(), "doctorMembershipId")).isEqualTo(f.doctor());
        var saved = call("POST", base + "/" + appointment + "/consultation-draft",
                doctor, "{\"expectedRevision\":0,\"consultationNote\":\"" + NOTE + "\"}");
        assertThat(saved.statusCode()).isEqualTo(200);
        assertThat(saved.body()).contains("\"revision\":1", NOTE);
        assertThat(call("POST", "/api/v1/auth/logout", doctor, "{}")
                .statusCode()).isBetween(200, 204);
        long pid = ProcessHandle.current().pid();
        assertThat(admin.update("""
                UPDATE qa_guard.pilot05_run_marker SET
                    tenant_id=?,branch_id=?,patient_id=?,appointment_id=?,doctor_id=?,
                    process_a=?,phase='READY_A'
                WHERE run_id=? AND phase='READY'
                """, f.tenant(), f.branch(), f.patient(), appointment, f.doctor(),
                pid, Pilot05DisposableDatabaseGuard.runId())).isOne();
        System.out.println("PILOT05_PHASE_A_PASS JVM_PID=" + pid);
        System.out.println("PILOT08_RECEPTION_PHASE_A_REGISTRATION_BOOK_CHECKIN_QUEUE_PASS");
    }

    @Test
    void phaseBReopensSameConsultationInDifferentJvm() throws Exception {
        assertThat(Pilot05DisposableDatabaseGuard.env("ARONEXA_PILOT05_PHASE"))
                .isEqualTo("B");
        var markers = admin.queryForList("""
                SELECT tenant_id,branch_id,patient_id,appointment_id,doctor_id,
                       process_a,phase FROM qa_guard.pilot05_run_marker WHERE run_id=?
                """, Pilot05DisposableDatabaseGuard.runId());
        assertThat(markers).hasSize(1);
        var m = markers.getFirst();
        assertThat(m.get("phase")).isEqualTo("READY_A");
        long firstPid = ((Number)m.get("process_a")).longValue();
        long secondPid = ProcessHandle.current().pid();
        assertThat(secondPid).isNotEqualTo(firstPid);
        UUID branch = (UUID)m.get("branch_id");
        UUID patient = (UUID)m.get("patient_id");
        UUID appointment = (UUID)m.get("appointment_id");
        UUID doctorId = (UUID)m.get("doctor_id");
        assertThat(patient).isNotNull();
        assertThat(appointment).isNotNull();
        String doctor = login(doctorUsername());
        String base = "/api/v1/branches/" + branch + "/appointments/" + appointment;
        var context = call("GET", base + "/consultation-context", doctor, null);
        assertThat(context.statusCode()).isEqualTo(200);
        assertThat(extract(context.body(), "patientId")).isEqualTo(patient);
        assertThat(extract(context.body(), "appointmentId")).isEqualTo(appointment);
        assertThat(extract(context.body(), "doctorMembershipId")).isEqualTo(doctorId);
        var readback = call("GET", base + "/consultation-draft", doctor, null);
        assertThat(readback.statusCode()).isEqualTo(200);
        assertThat(extract(readback.body(), "patientId")).isEqualTo(patient);
        assertThat(readback.body()).contains("\"revision\":1", NOTE);

        String reception = login(receptionUsername());
        // PILOT-08 RC01: this is a fresh server session in a distinct
        // JVM. The same marked disposable PG17 must retain registration,
        // booking/check-in and the date-filtered daily queue.
        UUID registration=admin.queryForObject("""
            SELECT id FROM public.patient_registration
            WHERE tenant_id=? AND branch_id=? AND patient_id=?
            """,UUID.class,m.get("tenant_id"),branch,patient);
        assertThat(registration).isNotNull();
        var recovered=call("GET","/api/v1/branches/"+branch
                +"/patient-registrations/"+registration,reception,null);
        assertThat(recovered.statusCode()).isEqualTo(200);
        assertThat(recovered.body()).contains(
                registration.toString(),patient.toString());
        var queue=call("GET","/api/v1/branches/"+branch+
                "/appointments?visitDate="+LocalDate.now(CLINIC_ZONE),
                reception,null);
        assertThat(queue.statusCode()).isEqualTo(200);
        assertThat(queue.body()).contains(
                appointment.toString(),registration.toString(),
                "\"stage\":\"CHECKED_IN\"");
        // The receptionist cannot read the doctor's consultation content.
        assertThat(call("GET", base + "/consultation-draft", reception, null)
                .statusCode()).isEqualTo(403);
        assertThat(admin.queryForObject("""
            SELECT count(*) FROM public.patient_registration_access_audit
            WHERE tenant_id=? AND branch_id=? AND registration_id=?
              AND action='READ'
            """,Integer.class,m.get("tenant_id"),branch,registration))
            .isGreaterThanOrEqualTo(2);
        assertThat(admin.queryForObject("""
                SELECT count(*) FROM appointment_proof.consultation_revision
                WHERE appointment_id=? AND consultation_note=?
                """, Integer.class, appointment, NOTE)).isOne();
        assertThat(admin.update("""
                UPDATE qa_guard.pilot05_run_marker SET process_b=?,phase='PASS_B'
                WHERE run_id=? AND phase='READY_A'
                """, secondPid, Pilot05DisposableDatabaseGuard.runId())).isOne();
        System.out.println("PILOT05_PHASE_B_PASS JVM_PID=" + secondPid);
        System.out.println("PILOT08_RECEPTION_PHASE_B_FRESH_SESSION_REGISTRATION_QUEUE_PASS");
    }
}
