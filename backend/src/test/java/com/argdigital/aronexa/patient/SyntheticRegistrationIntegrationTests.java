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
                           UUID branch, UUID otherBranch, UUID session) {}

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
        UUID session=UUID.randomUUID();
        admin.update("INSERT INTO auth_session(id,membership_id,tenant_id,token_sha256,expires_at) VALUES (?,?,?,?,now()+interval '30 minutes')",
                session,a,t,UUID.randomUUID().toString().replace("-","").repeat(2));
        return new Fixture(t,other,a,another,b,otherBranch,session);
    }
    private void as(Fixture f, UUID member, boolean passwordRequired, String... perms) {
        UUID session=member.equals(f.member())?f.session():UUID.randomUUID();
        if (!member.equals(f.member())) {
            admin.update("INSERT INTO auth_session(id,membership_id,tenant_id,token_sha256,expires_at) VALUES (?,?,?,?,now()+interval '30 minutes')",
                    session,member,f.tenant(),UUID.randomUUID().toString().replace("-","").repeat(2));
        }
        var principal=new AuthPrincipal(UUID.randomUUID(),f.tenant(),member,"synthetic",
                passwordRequired,List.of("RECEPTION"),List.of(perms),session);
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
        return SyntheticRegistrationProof.sign(EPHEMERAL_PROOF_KEY,1, f.tenant(), f.member(),
                f.session(), f.branch(), name, "UNKNOWN", null,
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
        return proofCallWithKey(connection,f,1,name,mrn,nonce,expiry,signature);
    }
    private static java.sql.PreparedStatement proofCallWithKey(Connection connection, Fixture f,
            int keyId, String name, String mrn, UUID nonce, long expiry, String signature) throws Exception {
        var ps=connection.prepareStatement(
                "SELECT * FROM public.aronexa_c2_register_patient(?,?,?,?,?,?,?,?,?,?,?,?,?,?)");
        ps.setObject(1,f.tenant());
        ps.setObject(2,f.member());
        ps.setObject(3,f.session());
        ps.setObject(4,f.branch());
        ps.setString(5,name);
        ps.setString(6,"UNKNOWN");
        ps.setNull(7,java.sql.Types.DATE);
        ps.setString(8,"a".repeat(64));
        ps.setString(9,"b".repeat(64));
        ps.setString(10,mrn);
        ps.setInt(11,keyId);
        ps.setObject(12,nonce);
        ps.setLong(13,expiry);
        ps.setString(14,signature);
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

    @Test void secondaryKeyCanBeActivatedAndRetiredWithoutLeakingOrCrossSigning() throws Exception {
        Fixture f=fixture();
        byte[] second=new byte[32];
        new SecureRandom().nextBytes(second);
        // Synthetic migrator fixture only. No runtime SQL login can UPDATE keys.
        admin.update("""
                INSERT INTO public.patient_registration_proof_key
                    (id,secret,enabled,valid_from,valid_until)
                VALUES (2,?,true,clock_timestamp()-interval '1 minute',
                          clock_timestamp()+interval '1 hour')
                """,second);
        String name="Synthetic Rotating Key",mrn="PAT-0123456789AB";
        long expiry=java.time.Instant.now().plusSeconds(30).getEpochSecond();
        UUID nonce=UUID.randomUUID();
        String signature=SyntheticRegistrationProof.sign(second,2,
                f.tenant(),f.member(),f.session(),f.branch(),name,"UNKNOWN",null,
                "a".repeat(64),"b".repeat(64),mrn,nonce,expiry);
        try(Connection connection=DriverManager.getConnection(postgres.getJdbcUrl(),
                "aronexa_patient_runtime","only-for-ephemeral-patient-role")) {
            connection.setAutoCommit(false);
            bindSqlRoleContext(connection,f);
            // A valid ID=2 signature cannot be relabeled as ID=1.
            try(var bad=proofCallWithKey(connection,f,1,name,mrn,nonce,expiry,signature)) {
                assertThatThrownBy(bad::executeQuery)
                        .isInstanceOf(java.sql.SQLException.class)
                        .hasMessageContaining("C2_PROOF_DENIED");
            }
            connection.rollback();
            connection.setAutoCommit(false);
            bindSqlRoleContext(connection,f);
            try(var valid=proofCallWithKey(connection,f,2,name,mrn,nonce,expiry,signature);
                var rs=valid.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getBoolean("replayed")).isFalse();
            }
            connection.commit();
            admin.update("UPDATE public.patient_registration_proof_key SET enabled=false WHERE id=2");
            UUID disabledNonce=UUID.randomUUID();
            String disabledName="Synthetic Retired Key";
            String disabledSig=SyntheticRegistrationProof.sign(second,2,
                    f.tenant(),f.member(),f.session(),f.branch(),disabledName,"UNKNOWN",null,
                    "a".repeat(64),"b".repeat(64),"PAT-0123456789AC",disabledNonce,expiry);
            connection.setAutoCommit(false);
            bindSqlRoleContext(connection,f);
            try(var disabled=proofCallWithKey(connection,f,2,disabledName,"PAT-0123456789AC",
                    disabledNonce,expiry,disabledSig)) {
                assertThatThrownBy(disabled::executeQuery)
                        .isInstanceOf(java.sql.SQLException.class)
                        .hasMessageContaining("C2_PROOF_DENIED");
            }
            connection.rollback();
            admin.update("""
                    UPDATE public.patient_registration_proof_key
                       SET enabled=true, valid_from=clock_timestamp()+interval '1 hour',
                           valid_until=clock_timestamp()+interval '2 hours'
                     WHERE id=2
                    """);
            connection.setAutoCommit(false);
            bindSqlRoleContext(connection,f);
            try(var future=proofCallWithKey(connection,f,2,disabledName,"PAT-0123456789AC",
                    disabledNonce,expiry,disabledSig)) {
                assertThatThrownBy(future::executeQuery)
                        .isInstanceOf(java.sql.SQLException.class)
                        .hasMessageContaining("C2_PROOF_DENIED");
            }
            connection.rollback();
            admin.update("""
                    UPDATE public.patient_registration_proof_key
                       SET valid_from=clock_timestamp()-interval '2 hours',
                           valid_until=clock_timestamp()-interval '1 hour'
                     WHERE id=2
                    """);
            connection.setAutoCommit(false);
            bindSqlRoleContext(connection,f);
            try(var expired=proofCallWithKey(connection,f,2,disabledName,"PAT-0123456789AC",
                    disabledNonce,expiry,disabledSig)) {
                assertThatThrownBy(expired::executeQuery)
                        .isInstanceOf(java.sql.SQLException.class)
                        .hasMessageContaining("C2_PROOF_DENIED");
            }
            connection.rollback();
        }
        assertThat(admin.queryForObject(
                "SELECT count(*) FROM public.patient WHERE tenant_id=?",Integer.class,f.tenant())).isEqualTo(1);
        assertThat(admin.queryForObject(
                "SELECT count(*) FROM public.patient_registration_proof_nonce WHERE tenant_id=?",
                Integer.class,f.tenant())).isEqualTo(1);
    }

    @Test void revokedOrExpiredSessionIsDeniedAtDatabaseBoundary() {
        Fixture revoked=fixture(); as(revoked,revoked.member(),false,"PATIENT_REGISTER");
        admin.update("UPDATE auth_session SET revoked_at=clock_timestamp() WHERE id=?",revoked.session());
        assertThatThrownBy(() -> service.register(revoked.branch(),
                intake("Synthetic Revoked After Authentication"),key()))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(admin.queryForObject("SELECT count(*) FROM patient WHERE tenant_id=?",
                Integer.class,revoked.tenant())).isZero();

        Fixture expired=fixture(); as(expired,expired.member(),false,"PATIENT_REGISTER");
        admin.update("UPDATE auth_session SET issued_at=now()-interval '2 hours', expires_at=now()-interval '1 hour' WHERE id=?",
                expired.session());
        assertThatThrownBy(() -> service.register(expired.branch(),
                intake("Synthetic Expired After Authentication"),key()))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(admin.queryForObject("SELECT count(*) FROM patient WHERE tenant_id=?",
                Integer.class,expired.tenant())).isZero();
    }

    @Test void mrnCollisionRollsBackThenRetriesWithFreshTransactionAndProof() {
        Fixture f=fixture(); as(f,f.member(),false,"PATIENT_REGISTER");
        var initial=service.register(f.branch(),intake("Synthetic First MRN Owner"),key());
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        var recovered=service.registerWithSyntheticMrnSource(
                f.branch(),intake("Synthetic MRN Recovered"),key(),() ->
                        calls.getAndIncrement()==0?initial.mrn():
                        PatientRegistrationRules.generateMrn(new SecureRandom()));
        assertThat(calls.get()).isEqualTo(2);
        assertThat(recovered.patientId()).isNotEqualTo(initial.patientId());
        assertThat(recovered.mrn()).isNotEqualTo(initial.mrn());
        assertThat(admin.queryForObject("SELECT count(*) FROM patient WHERE tenant_id=?",
                Integer.class,f.tenant())).isEqualTo(2);
        assertThat(admin.queryForObject("SELECT count(*) FROM patient_identifier WHERE tenant_id=?",
                Integer.class,f.tenant())).isEqualTo(2);
        assertThat(admin.queryForObject("SELECT count(*) FROM patient_registration_write_audit WHERE tenant_id=?",
                Integer.class,f.tenant())).isEqualTo(2);
        assertThat(admin.queryForObject("SELECT count(*) FROM patient_registration_proof_nonce WHERE tenant_id=?",
                Integer.class,f.tenant())).isEqualTo(2);
    }

    @Test void threeRepeatedMrnCollisionsFailClosedWithoutOrphanData() {
        Fixture f=fixture(); as(f,f.member(),false,"PATIENT_REGISTER");
        var initial=service.register(f.branch(),intake("Synthetic Original MRN"),key());
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        assertThatThrownBy(() -> service.registerWithSyntheticMrnSource(
                f.branch(),intake("Synthetic All Collisions"),key(),() -> {
                    calls.incrementAndGet();
                    return initial.mrn();
                })).isInstanceOf(SyntheticRegistrationService.RegistrationRejected.class)
                .hasMessage("REGISTRATION_UNAVAILABLE");
        assertThat(calls.get()).isEqualTo(3);
        for(String table:new String[] {
                "patient","patient_identifier","patient_registration",
                "patient_registration_write_audit","patient_registration_idempotency",
                "patient_registration_proof_nonce"
        }) {
            assertThat(admin.queryForObject("SELECT count(*) FROM "+table+" WHERE tenant_id=?",
                    Integer.class,f.tenant())).as("orphan data in "+table).isEqualTo(1);
        }
    }


    /**
     * C2-S2-C1: registration obtains FOR SHARE on the authentic bearer session.
     * The real auth LOGIN must wait to revoke until that registration commits.
     * PostgreSQL lock_timeout makes the test bounded and deterministic; the
     * test does not sleep or depend on thread interleaving.
     */
    @Test void registrationSessionLockSerializesCompetingLogoutThenRevokedTokenCannotWrite() throws Exception {
        Fixture f=fixture();
        String name="Synthetic Registration First";
        String mrn="PAT-0123456789AB";
        UUID nonce=UUID.randomUUID();
        long expiry=java.time.Instant.now().plusSeconds(30).getEpochSecond();
        try(Connection patient=DriverManager.getConnection(postgres.getJdbcUrl(),
                "aronexa_patient_runtime","only-for-ephemeral-patient-role");
            Connection auth=DriverManager.getConnection(postgres.getJdbcUrl(),
                "aronexa_auth_runtime","only-for-ephemeral-auth-role")) {
            patient.setAutoCommit(false);
            bindSqlRoleContext(patient,f);
            try(var ps=proofCall(patient,f,name,mrn,nonce,expiry,
                    syntheticProof(f,name,mrn,nonce,expiry));
                var rs=ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("mrn")).isEqualTo(mrn);
            }
            // A competing auth runtime cannot commit session revocation while
            // this registration transaction retains its FOR SHARE lock.
            auth.setAutoCommit(false);
            try(Statement s=auth.createStatement()) {
                s.execute("SET LOCAL lock_timeout = '400ms'");
            }
            try(var revoke=auth.prepareStatement(
                    "UPDATE public.auth_session SET revoked_at=clock_timestamp() WHERE id=?")) {
                revoke.setObject(1,f.session());
                assertThatThrownBy(revoke::executeUpdate)
                        .isInstanceOf(java.sql.SQLException.class)
                        .hasMessageContaining("canceling statement due to lock timeout");
            }
            auth.rollback();
            patient.commit();
            try(var revoke=auth.prepareStatement(
                    "UPDATE public.auth_session SET revoked_at=clock_timestamp() WHERE id=?")) {
                revoke.setObject(1,f.session());
                assertThat(revoke.executeUpdate()).isEqualTo(1);
            }
            auth.commit();
        }
        as(f,f.member(),false,"PATIENT_REGISTER");
        assertThatThrownBy(() -> service.register(f.branch(),
                intake("Synthetic After Logout"),key()))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(admin.queryForObject(
                "SELECT count(*) FROM public.patient WHERE tenant_id=?",Integer.class,f.tenant()))
                .isEqualTo(1);
        assertThat(admin.queryForObject(
                "SELECT count(*) FROM public.patient_registration_write_audit WHERE tenant_id=?",
                Integer.class,f.tenant())).isEqualTo(1);
        assertThat(admin.queryForObject(
                "SELECT count(*) FROM public.patient_registration_proof_nonce WHERE tenant_id=?",
                Integer.class,f.tenant())).isEqualTo(1);
    }

    /**
     * The inverse order: an auth UPDATE holds the session row lock first.
     * Registration must wait, and after revocation commits must be denied.
     */
    @Test void pendingLogoutPreventsSignedRegistrationUntilRevocationCommits() throws Exception {
        Fixture f=fixture();
        String name="Synthetic Logout First", mrn="PAT-0123456789AB";
        long expiry=java.time.Instant.now().plusSeconds(30).getEpochSecond();
        try(Connection auth=DriverManager.getConnection(postgres.getJdbcUrl(),
                "aronexa_auth_runtime","only-for-ephemeral-auth-role");
            Connection patient=DriverManager.getConnection(postgres.getJdbcUrl(),
                "aronexa_patient_runtime","only-for-ephemeral-patient-role")) {
            auth.setAutoCommit(false);
            try(var revoke=auth.prepareStatement(
                    "UPDATE public.auth_session SET revoked_at=clock_timestamp() WHERE id=?")) {
                revoke.setObject(1,f.session());
                assertThat(revoke.executeUpdate()).isEqualTo(1);
            }
            patient.setAutoCommit(false);
            bindSqlRoleContext(patient,f);
            try(Statement s=patient.createStatement()) {
                s.execute("SET LOCAL lock_timeout = '400ms'");
            }
            UUID waitingNonce=UUID.randomUUID();
            try(var waiting=proofCall(patient,f,name,mrn,waitingNonce,expiry,
                    syntheticProof(f,name,mrn,waitingNonce,expiry))) {
                assertThatThrownBy(waiting::executeQuery)
                        .isInstanceOf(java.sql.SQLException.class)
                        .hasMessageContaining("canceling statement due to lock timeout");
            }
            patient.rollback();
            auth.commit();
            patient.setAutoCommit(false);
            bindSqlRoleContext(patient,f);
            UUID deniedNonce=UUID.randomUUID();
            try(var denied=proofCall(patient,f,name,mrn,deniedNonce,expiry,
                    syntheticProof(f,name,mrn,deniedNonce,expiry))) {
                assertThatThrownBy(denied::executeQuery)
                        .isInstanceOf(java.sql.SQLException.class)
                        .hasMessageContaining("C2_SESSION_REVOKED");
            }
            patient.rollback();
        }
        for(String table:new String[] {
                "patient","patient_identifier","patient_registration",
                "patient_registration_write_audit","patient_registration_idempotency",
                "patient_registration_proof_nonce"
        }) {
            assertThat(admin.queryForObject(
                    "SELECT count(*) FROM public."+table+" WHERE tenant_id=?",
                    Integer.class,f.tenant()))
                    .as("post-logout attempt must not write "+table).isZero();
        }
    }


    /**
     * Exploratory release-blocker reproducer (NOT a secure acceptance test).
     * An authorized registration finishes its SQL statement while the patient
     * transaction remains open. A privileged operator commits revocation of
     * role, branch, or module on a separate connection. Since this function
     * locks only auth_session, the patient transaction can commit afterward.
     *
     * A passing test documents the CURRENT POLICY GAP; it does not certify
     * that revocation is safe. Never run against the original Windows DB.
     */
    private void demonstrateUnserializedRevocation(String dimension) throws Exception {
        Fixture f=fixture();
        String name="Synthetic Pre-Commit Revocation "+dimension;
        String mrn="PAT-0123456789AB";
        UUID nonce=UUID.randomUUID();
        long expiry=java.time.Instant.now().plusSeconds(30).getEpochSecond();
        try(Connection patient=DriverManager.getConnection(postgres.getJdbcUrl(),
                "aronexa_patient_runtime","only-for-ephemeral-patient-role");
            Connection operator=DriverManager.getConnection(postgres.getJdbcUrl(),
                postgres.getUsername(),postgres.getPassword())) {
            patient.setAutoCommit(false);
            bindSqlRoleContext(patient,f);
            try(var request=proofCall(patient,f,name,mrn,nonce,expiry,
                    syntheticProof(f,name,mrn,nonce,expiry));
                var result=request.executeQuery()) {
                assertThat(result.next()).isTrue();
                assertThat(result.getBoolean("replayed")).isFalse();
            }
            // Keep patient transaction OPEN; revocation must genuinely commit
            // on a separate privileged connection before patient.commit().
            operator.setAutoCommit(false);
            try(Statement timeout=operator.createStatement()) {
                timeout.execute("SET LOCAL statement_timeout = '1200ms'");
            }
            String revokeSql=switch(dimension) {
                case "ROLE" -> "DELETE FROM public.auth_membership_role WHERE tenant_id=? AND membership_id=?";
                case "BRANCH" -> "DELETE FROM public.org_membership_branch_scope WHERE tenant_id=? AND membership_id=? AND branch_id=?";
                case "MODULE" -> "UPDATE public.tenant_module_state SET activated=false WHERE tenant_id=? AND module_code='PATIENT_REGISTRATION'";
                default -> throw new IllegalArgumentException("Unknown synthetic revocation");
            };
            try(var revoke=operator.prepareStatement(revokeSql)) {
                revoke.setObject(1,f.tenant());
                if(!dimension.equals("MODULE")) revoke.setObject(2,f.member());
                if(dimension.equals("BRANCH")) revoke.setObject(3,f.branch());
                assertThat(revoke.executeUpdate())
                        .as("synthetic privileged operator must commit "+dimension+" revocation")
                        .isEqualTo(1);
            }
            operator.commit();
            // Existing implementation permits this commit, even though the
            // authorization row is already revoked. This is a NO-GO finding.
            patient.commit();
        }
        assertThat(admin.queryForObject(
                "SELECT count(*) FROM public.patient WHERE tenant_id=?",
                Integer.class,f.tenant())).isEqualTo(1);
        assertThat(admin.queryForObject(
                "SELECT count(*) FROM public.patient_registration_write_audit WHERE tenant_id=?",
                Integer.class,f.tenant())).isEqualTo(1);
        assertThat(admin.queryForObject(
                "SELECT count(*) FROM public.patient_registration_proof_nonce WHERE tenant_id=?",
                Integer.class,f.tenant())).isEqualTo(1);
        assertThat(admin.queryForObject(
                "SELECT count(*) FROM public.patient_registration_idempotency WHERE tenant_id=?",
                Integer.class,f.tenant())).isEqualTo(1);
    }

    @Test void exploratoryRoleRevocationCommitsBeforeRegistrationCommit() throws Exception {
        demonstrateUnserializedRevocation("ROLE");
    }
    @Test void exploratoryBranchRevocationCommitsBeforeRegistrationCommit() throws Exception {
        demonstrateUnserializedRevocation("BRANCH");
    }
    @Test void exploratoryModuleRevocationCommitsBeforeRegistrationCommit() throws Exception {
        demonstrateUnserializedRevocation("MODULE");
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
