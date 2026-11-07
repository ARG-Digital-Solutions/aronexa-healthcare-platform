package com.argdigital.aronexa.patient;

import com.argdigital.aronexa.auth.AuthPrincipal;
import java.security.SecureRandom;
import java.util.Base64;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@ActiveProfiles({"test","c2-synthetic"})
@TestPropertySource(properties =
        "aronexa.c2.synthetic-hmac-base64=QUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUE=")
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SyntheticRegistrationIntegrationTests {
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
            .withDatabaseName("aronexa_c2_isolated_only")
            .withUsername("aronexa_c2_isolated_only")
            .withPassword("isolated-test-migrator");

    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate admin;
    @Autowired SyntheticRegistrationService service;

    private record Fixture(UUID tenant, UUID otherTenant, UUID member, UUID otherMember,
                           UUID branch, UUID otherBranch) {}

    @BeforeAll
    void migrateProposedSqlInEphemeralDatabaseOnly() {
        Flyway migration = Flyway.configure().dataSource(dataSource)
                .locations("classpath:db/migration","classpath:db/proposed").load();
        assertThat(migration.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(migration.info().current().getVersion().getVersion()).isEqualTo("10");
        assertThat(migration.validateWithResult().validationSuccessful).isTrue();
        // Synthetic fixture only: no proof key is shipped by Flyway migration.
        admin.update("INSERT INTO public.patient_registration_proof_key(id,secret) VALUES (1,?)",
                EPHEMERAL_PROOF_KEY);
    }

    @AfterEach
    void resetSecurityContext() { SecurityContextHolder.clearContext(); }

    private UUID tenant() {
        UUID id=UUID.randomUUID();
        admin.update("INSERT INTO tenant(id,code,display_name) VALUES (?,?,?)",
                id,"c2_"+id.toString().substring(0,8),"Synthetic tenant");
        return id;
    }
    private UUID member(UUID tenant) {
        UUID user=UUID.randomUUID(), membership=UUID.randomUUID();
        admin.update("INSERT INTO app_user(id,username,password_hash,must_change_password) VALUES (?,?,?,false)",
                user,"c2_"+user.toString().substring(0,8),"test-only");
        admin.update("INSERT INTO user_tenant_membership(id,user_id,tenant_id) VALUES (?,?,?)",
                membership,user,tenant);
        return membership;
    }
    private UUID branch(UUID tenant) {
        UUID f=UUID.randomUUID(), b=UUID.randomUUID();
        admin.update("INSERT INTO facility(id,tenant_id,code,display_name,facility_kind) VALUES (?,?,?,'Test clinic','CLINIC')",
                f,tenant,"f_"+f.toString().substring(0,8));
        admin.update("INSERT INTO branch(id,tenant_id,facility_id,code,display_name) VALUES (?,?,?,?,'Test branch')",
                b,tenant,f,"b_"+b.toString().substring(0,8));
        return b;
    }
    private Fixture fixture() {
        UUID t=tenant(),other=tenant(),a=member(t),another=member(t),b=branch(t),otherBranch=branch(other);
        admin.update("INSERT INTO org_membership_branch_scope(tenant_id,membership_id,branch_id) VALUES (?,?,?)",t,a,b);
        UUID role=UUID.randomUUID();
        admin.update("INSERT INTO auth_role(id,tenant_id,code,display_name) VALUES (?,?,'RECEPTION','Receptionist')",role,t);
        admin.update("INSERT INTO auth_role_permission(tenant_id,role_id,permission_code) VALUES (?,?,'PATIENT_REGISTER')",t,role);
        admin.update("INSERT INTO auth_membership_role(tenant_id,membership_id,role_id) VALUES (?,?,?)",t,a,role);
        // Explicit, ephemeral TEST fixture only. Migration itself enables no module/permission.
        admin.update("UPDATE module_registry SET implemented=true,released=true WHERE code='PATIENT_REGISTRATION'");
        admin.update("INSERT INTO tenant_module_state(tenant_id,module_code,entitled,activated,clinically_authorized) VALUES (?,'PATIENT_REGISTRATION',true,true,true)",t);
        return new Fixture(t,other,a,another,b,otherBranch);
    }
    private void as(Fixture f, UUID member, boolean passwordRequired, String... perms) {
        var principal=new AuthPrincipal(UUID.randomUUID(),f.tenant(),member,"synthetic",
                passwordRequired,List.of("RECEPTION"),List.of(perms));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal,null,List.of()));
    }
    private static PatientRegistrationRules.Intake intake(String name) {
        return new PatientRegistrationRules.Intake(name,
                PatientRegistrationRules.BirthDatePrecision.UNKNOWN,null);
    }
    private static String key(){return "synthetic-key-"+UUID.randomUUID().toString().replace("-","");}

    @Test void createReplayAndActorAuditAreAtomic() {
        Fixture f=fixture(); as(f,f.member(),false,"PATIENT_REGISTER");
        String idem=key();
        var first=service.register(f.branch(),intake("Synthetic C2 Alice"),idem);
        assertThat(first.replayed()).isFalse();
        assertThat(first.mrn()).matches("PAT-[0-9A-HJKMNP-TV-Z]{12}");
        var retry=service.register(f.branch(),intake("Synthetic C2 Alice"),idem);
        assertThat(retry.replayed()).isTrue();
        assertThat(retry.patientId()).isEqualTo(first.patientId());
        assertThat(retry.registrationId()).isEqualTo(first.registrationId());
        assertThat(retry.mrn()).isEqualTo(first.mrn());
        assertThat(admin.queryForObject("SELECT count(*) FROM patient WHERE tenant_id=? AND id=?",
                Integer.class,f.tenant(),first.patientId())).isEqualTo(1);
        assertThat(admin.queryForObject("SELECT count(*) FROM patient_identifier WHERE tenant_id=? AND patient_id=?",
                Integer.class,f.tenant(),first.patientId())).isEqualTo(1);
        assertThat(admin.queryForObject("SELECT count(*) FROM patient_registration WHERE tenant_id=? AND id=? AND registered_by_membership_id=?",
                Integer.class,f.tenant(),first.registrationId(),f.member())).isEqualTo(1);
        assertThat(admin.queryForObject("SELECT count(*) FROM patient_registration_write_audit WHERE tenant_id=? AND registration_id=? AND actor_membership_id=?",
                Integer.class,f.tenant(),first.registrationId(),f.member())).isEqualTo(1);
    }

    @Test void idempotencyMismatchAndExactDuplicateFailWithoutOrphans() {
        Fixture f=fixture(); as(f,f.member(),false,"PATIENT_REGISTER");
        String idem=key();
        service.register(f.branch(),intake("Synthetic C2 Duplicate"),idem);
        assertThatThrownBy(() -> service.register(f.branch(),intake("Synthetic C2 Modified"),idem))
                .isInstanceOf(SyntheticRegistrationService.RegistrationRejected.class)
                .hasMessage("IDEMPOTENCY_CONFLICT");
        assertThatThrownBy(() -> service.register(f.branch(),intake("Synthetic C2 Duplicate"),key()))
                .isInstanceOf(SyntheticRegistrationService.RegistrationRejected.class)
                .hasMessage("DUPLICATE_REVIEW_REQUIRED");
        assertThat(admin.queryForObject("SELECT count(*) FROM patient_registration r JOIN patient p ON p.id=r.patient_id AND p.tenant_id=r.tenant_id WHERE r.tenant_id=? AND p.display_name='Synthetic C2 Duplicate'",
                Integer.class,f.tenant())).isEqualTo(1);
        assertThat(admin.queryForObject("SELECT count(*) FROM patient_registration_idempotency WHERE tenant_id=?",
                Integer.class,f.tenant())).isEqualTo(1);
    }

    @Test void missingPermissionAndDifferentBranchAreDenied() {
        Fixture f=fixture();
        as(f,f.member(),false);
        assertThatThrownBy(() -> service.register(f.branch(),intake("Synthetic Unprivileged"),key()))
                .isInstanceOf(AccessDeniedException.class);
        as(f,f.member(),false,"PATIENT_REGISTER");
        assertThatThrownBy(() -> service.register(f.otherBranch(),intake("Synthetic Wrong Branch"),key()))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(admin.queryForObject("SELECT count(*) FROM patient WHERE tenant_id=?",
                Integer.class,f.tenant())).isZero();
    }

    @Test void passwordFlagDisabledModuleOrBranchRevocationDenyRegistration() {
        Fixture f=fixture();
        as(f,f.member(),true,"PATIENT_REGISTER");
        assertThatThrownBy(() -> service.register(f.branch(),intake("Synthetic Password"),key()))
                .isInstanceOf(AccessDeniedException.class);
        as(f,f.member(),false,"PATIENT_REGISTER");
        admin.update("UPDATE tenant_module_state SET activated=false WHERE tenant_id=? AND module_code='PATIENT_REGISTRATION'",f.tenant());
        assertThatThrownBy(() -> service.register(f.branch(),intake("Synthetic Disabled"),key()))
                .isInstanceOf(AccessDeniedException.class);
        admin.update("UPDATE tenant_module_state SET activated=true WHERE tenant_id=? AND module_code='PATIENT_REGISTRATION'",f.tenant());
        admin.update("DELETE FROM org_membership_branch_scope WHERE tenant_id=? AND membership_id=? AND branch_id=?",
                f.tenant(),f.member(),f.branch());
        assertThatThrownBy(() -> service.register(f.branch(),intake("Synthetic Revoked"),key()))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test void directTableWritesFromPatientLoginRemainDenied() throws Exception {
        Fixture f=fixture();
        try(Connection c=DriverManager.getConnection(postgres.getJdbcUrl(),
                "aronexa_patient_runtime","only-for-ephemeral-patient-role")) {
            try(Statement stmt=c.createStatement()) {
                for(String table:List.of("patient","patient_identifier","patient_registration",
                        "patient_registration_write_audit","patient_registration_idempotency")) {
                    final String sql="INSERT INTO public."+table+" DEFAULT VALUES";
                    assertThatThrownBy(()->stmt.executeUpdate(sql))
                            .as("must deny direct write on "+table).isInstanceOf(java.sql.SQLException.class);
                }
            }
        }
        assertThat(admin.queryForObject("SELECT count(*) FROM patient WHERE tenant_id=?",
                Integer.class,f.tenant())).isZero();
    }


    @Test void concurrentSameKeyProducesOnePatientAndStableReceipt() throws Exception {
        Fixture f=fixture();
        String idem=key();
        var executor=java.util.concurrent.Executors.newFixedThreadPool(2);
        var ready=new java.util.concurrent.CountDownLatch(2);
        var go=new java.util.concurrent.CountDownLatch(1);
        try {
            java.util.List<java.util.concurrent.Future<SyntheticRegistrationService.Receipt>> results =
                    new java.util.ArrayList<>();
            for(int i=0;i<2;i++) {
                results.add(executor.submit(() -> {
                    as(f,f.member(),false,"PATIENT_REGISTER");
                    try {
                        ready.countDown();
                        if(!go.await(10,java.util.concurrent.TimeUnit.SECONDS))
                            throw new IllegalStateException("Concurrent start timed out");
                        return service.register(f.branch(),intake("Synthetic Parallel Same Key"),idem);
                    } finally { SecurityContextHolder.clearContext(); }
                }));
            }
            assertThat(ready.await(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            go.countDown();
            var a=results.get(0).get(20,java.util.concurrent.TimeUnit.SECONDS);
            var b=results.get(1).get(20,java.util.concurrent.TimeUnit.SECONDS);
            assertThat(a.patientId()).isEqualTo(b.patientId());
            assertThat(a.registrationId()).isEqualTo(b.registrationId());
            assertThat(a.mrn()).isEqualTo(b.mrn());
            assertThat(a.replayed() || b.replayed()).isTrue();
            assertThat(admin.queryForObject("SELECT count(*) FROM patient WHERE tenant_id=? AND display_name='Synthetic Parallel Same Key'",
                    Integer.class,f.tenant())).isEqualTo(1);
        } finally {
            go.countDown();
            executor.shutdownNow();
        }
    }

    @Test void concurrentDifferentKeysSameNameNeverAutoMergeOrDoubleRegister() throws Exception {
        Fixture f=fixture();
        var executor=java.util.concurrent.Executors.newFixedThreadPool(2);
        var ready=new java.util.concurrent.CountDownLatch(2);
        var go=new java.util.concurrent.CountDownLatch(1);
        try {
            java.util.List<java.util.concurrent.Future<String>> results=new java.util.ArrayList<>();
            for(int i=0;i<2;i++) {
                String idem=key();
                results.add(executor.submit(() -> {
                    as(f,f.member(),false,"PATIENT_REGISTER");
                    try {
                        ready.countDown();
                        if(!go.await(10,java.util.concurrent.TimeUnit.SECONDS))
                            throw new IllegalStateException("Concurrent start timed out");
                        try {
                            service.register(f.branch(),intake("Synthetic Parallel Distinct Keys"),idem);
                            return "CREATED";
                        } catch(SyntheticRegistrationService.RegistrationRejected rejected) {
                            return rejected.code();
                        }
                    } finally { SecurityContextHolder.clearContext(); }
                }));
            }
            assertThat(ready.await(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            go.countDown();
            String a=results.get(0).get(20,java.util.concurrent.TimeUnit.SECONDS);
            String b=results.get(1).get(20,java.util.concurrent.TimeUnit.SECONDS);
            assertThat(java.util.List.of(a,b)).containsExactlyInAnyOrder("CREATED","DUPLICATE_REVIEW_REQUIRED");
            assertThat(admin.queryForObject("SELECT count(*) FROM patient WHERE tenant_id=? AND display_name='Synthetic Parallel Distinct Keys'",
                    Integer.class,f.tenant())).isEqualTo(1);
        } finally {
            go.countDown();
            executor.shutdownNow();
        }
    }

     private static String syntheticProof(Fixture f, String name, String mrn, UUID nonce, long expiry) {
        return SyntheticRegistrationProof.sign(EPHEMERAL_PROOF_KEY, f.tenant(), f.member(),
                f.branch(), name, "UNKNOWN", null,
                "a".repeat(64), "b".repeat(64), mrn, nonce, expiry);
    }
    private static void bindSqlRoleContext(Connection connection, Fixture f) throws Exception {
        try(var setTenant=connection.prepareStatement("SELECT set_config('aronexa.tenant_id',?,true)");
            var setActor=connection.prepareStatement("SELECT set_config('aronexa.membership_id',?,true)")) {
            setTenant.setString(1,f.tenant().toString());
            setTenant.executeQuery().close();
            setActor.setString(1,f.member().toString());
            setActor.executeQuery().close();
        }
    }
    private static java.sql.PreparedStatement proofCall(Connection connection, Fixture f,
            String name, String mrn, UUID nonce, long expiry, String signature) throws Exception {
        var ps=connection.prepareStatement(
                "SELECT * FROM public.aronexa_c2_register_patient(?,?,?,?,?,?,?,?,?,?,?,?)");
        ps.setObject(1,f.tenant());
        ps.setObject(2,f.member());
        ps.setObject(3,f.branch());
        ps.setString(4,name);
        ps.setString(5,"UNKNOWN");
        ps.setNull(6,java.sql.Types.DATE);
        ps.setString(7,"a".repeat(64));
        ps.setString(8,"b".repeat(64));
        ps.setString(9,mrn);
        ps.setObject(10,nonce);
        ps.setLong(11,expiry);
        ps.setString(12,signature);
        return ps;
    }

    @Test void stolenSqlLoginAndForgedSessionContextCannotCreatePatient() throws Exception {
        Fixture f=fixture();
        long expiry=java.time.Instant.now().plusSeconds(30).getEpochSecond();
        UUID nonce=UUID.randomUUID();
        try(Connection connection=DriverManager.getConnection(postgres.getJdbcUrl(),
                "aronexa_patient_runtime","only-for-ephemeral-patient-role")) {
            connection.setAutoCommit(false);
            // The compromised role can set the exact tenant and actor GUCs.
            bindSqlRoleContext(connection,f);
            try(var ps=proofCall(connection,f,"Synthetic Forged Staff",
                    "PAT-0123456789AB",nonce,expiry,"0".repeat(64))) {
                assertThatThrownBy(ps::executeQuery)
                        .isInstanceOf(java.sql.SQLException.class)
                        .hasMessageContaining("C2_PROOF_DENIED");
            }
            connection.rollback();
            try(var stmt=connection.createStatement()) {
                assertThatThrownBy(() -> stmt.executeQuery(
                        "SELECT secret FROM public.patient_registration_proof_key"))
                        .isInstanceOf(java.sql.SQLException.class);
            }
            connection.rollback();
        }
        assertThat(admin.queryForObject("SELECT count(*) FROM patient WHERE tenant_id=?",
                Integer.class,f.tenant())).isZero();
    }

    @Test void proofIsBoundToExactPayloadAndExpiry() throws Exception {
        Fixture f=fixture();
        UUID nonce=UUID.randomUUID();
        String name="Synthetic Tamper",mrn="PAT-0123456789AB";
        long expiry=java.time.Instant.now().plusSeconds(30).getEpochSecond();
        String proof=syntheticProof(f,name,mrn,nonce,expiry);
        try(Connection connection=DriverManager.getConnection(postgres.getJdbcUrl(),
                "aronexa_patient_runtime","only-for-ephemeral-patient-role")) {
            connection.setAutoCommit(false); bindSqlRoleContext(connection,f);
            try(var ps=proofCall(connection,f,"Synthetic Tampered",mrn,nonce,expiry,proof)) {
                assertThatThrownBy(ps::executeQuery)
                        .isInstanceOf(java.sql.SQLException.class)
                        .hasMessageContaining("C2_PROOF_DENIED");
            }
            connection.rollback();
            connection.setAutoCommit(false);bindSqlRoleContext(connection,f);
            try(var ps=proofCall(connection,f,name,"PAT-0123456789AC",nonce,expiry,proof)) {
                assertThatThrownBy(ps::executeQuery)
                        .isInstanceOf(java.sql.SQLException.class)
                        .hasMessageContaining("C2_PROOF_DENIED");
            }
            connection.rollback();
            connection.setAutoCommit(false);bindSqlRoleContext(connection,f);
            long past=java.time.Instant.now().minusSeconds(20).getEpochSecond();
            UUID expiredNonce=UUID.randomUUID();
            try(var ps=proofCall(connection,f,name,mrn,expiredNonce,past,
                    syntheticProof(f,name,mrn,expiredNonce,past))) {
                assertThatThrownBy(ps::executeQuery)
                        .isInstanceOf(java.sql.SQLException.class)
                        .hasMessageContaining("C2_PROOF_DENIED");
            }
            connection.rollback();
        }
        assertThat(admin.queryForObject("SELECT count(*) FROM patient WHERE tenant_id=?",
                Integer.class,f.tenant())).isZero();
    }

    @Test void onceUsedValidProofCannotBeReplayedDirectlyFromStolenSqlLogin() throws Exception {
        Fixture f=fixture();
        UUID nonce=UUID.randomUUID();
        String name="Synthetic One Use",mrn="PAT-0123456789AB";
        long expiry=java.time.Instant.now().plusSeconds(30).getEpochSecond();
        String proof=syntheticProof(f,name,mrn,nonce,expiry);
        try(Connection connection=DriverManager.getConnection(postgres.getJdbcUrl(),
                "aronexa_patient_runtime","only-for-ephemeral-patient-role")) {
            connection.setAutoCommit(false);bindSqlRoleContext(connection,f);
            try(var ps=proofCall(connection,f,name,mrn,nonce,expiry,proof);
                var rs=ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("mrn")).isEqualTo(mrn);
            }
            connection.commit();
            connection.setAutoCommit(false);bindSqlRoleContext(connection,f);
            try(var ps=proofCall(connection,f,name,mrn,nonce,expiry,proof)) {
                assertThatThrownBy(ps::executeQuery)
                        .isInstanceOf(java.sql.SQLException.class)
                        .hasMessageContaining("C2_PROOF_REPLAY");
            }
            connection.rollback();
        }
        assertThat(admin.queryForObject("SELECT count(*) FROM patient WHERE tenant_id=?",
                Integer.class,f.tenant())).isEqualTo(1);
        assertThat(admin.queryForObject("SELECT count(*) FROM patient_registration_proof_nonce WHERE tenant_id=?",
                Integer.class,f.tenant())).isEqualTo(1);
    }

    @Test void allThreeSqlLoginRolesCannotReadProofSecretOrNonceLedger() throws Exception {
        for (String[] role : new String[][] {
                {"aronexa_patient_runtime","only-for-ephemeral-patient-role"},
                {"aronexa_app_runtime","only-for-ephemeral-test-container"},
                {"aronexa_auth_runtime","only-for-ephemeral-auth-role"}
        }) {
            try(Connection connection=DriverManager.getConnection(postgres.getJdbcUrl(),role[0],role[1]);
                Statement statement=connection.createStatement()) {
                assertThatThrownBy(() -> statement.executeQuery(
                        "SELECT secret FROM public.patient_registration_proof_key"))
                        .as("verifier secret readable by "+role[0])
                        .isInstanceOf(java.sql.SQLException.class);
                assertThatThrownBy(() -> statement.executeQuery(
                        "SELECT nonce FROM public.patient_registration_proof_nonce"))
                        .as("nonce ledger readable by "+role[0])
                        .isInstanceOf(java.sql.SQLException.class);
                assertThatThrownBy(() -> statement.executeUpdate(
                        "UPDATE public.patient_registration_proof_key SET secret=decode('AA','hex') WHERE id=1"))
                        .as("proof key writable by "+role[0])
                        .isInstanceOf(java.sql.SQLException.class);
                assertThatThrownBy(() -> statement.executeUpdate(
                        "INSERT INTO public.patient_registration_proof_nonce DEFAULT VALUES"))
                        .as("nonce ledger directly writable by "+role[0])
                        .isInstanceOf(java.sql.SQLException.class);
            }
        }
    }

    @Test void missingContextCannotInvokeFunctionDirectly() throws Exception {
        Fixture f=fixture();
        UUID nonce=UUID.randomUUID();
        long expiry=java.time.Instant.now().plusSeconds(30).getEpochSecond();
        try(Connection connection=DriverManager.getConnection(postgres.getJdbcUrl(),
                "aronexa_patient_runtime","only-for-ephemeral-patient-role");
            var ps=proofCall(connection,f,"Synthetic Unbound","PAT-0123456789AB",
                    nonce,expiry,syntheticProof(f,"Synthetic Unbound","PAT-0123456789AB",nonce,expiry))) {
            assertThatThrownBy(ps::executeQuery)
                    .isInstanceOf(java.sql.SQLException.class)
                    .hasMessageContaining("C2_ACCESS_DENIED");
        }
    }
}
