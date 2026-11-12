package com.argdigital.aronexa.patient;

import com.argdigital.aronexa.auth.AuthPrincipal;
import com.argdigital.aronexa.auth.TenantAuthorization;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.springframework.transaction.PlatformTransactionManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A real PostgreSQL17 Testcontainer runs the REVIEW-ONLY V10 SQL candidate.
 * Never uses the original Windows database, any clinic records or application
 * startup migration. No production registration API/controller is enabled.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RegistrationCandidateSqlAcceptanceTests {
    private static final byte[] SIGNING_KEY=new byte[32];
    private static final byte[] IDEMPOTENCY_SECRET=new byte[32];
    static {
        SecureRandom random=new SecureRandom();
        random.nextBytes(SIGNING_KEY);
        random.nextBytes(IDEMPOTENCY_SECRET);
    }

    @Container @ServiceConnection
    static PostgreSQLContainer postgres=new PostgreSQLContainer("postgres:17-alpine")
            .withInitScript("db/test/role-bootstrap.sql")
            .withDatabaseName("aronexa_review_v10_only")
            .withUsername("aronexa_review_v10_only")
            .withPassword("ephemeral-review-migrator");

    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate admin;
    @Autowired @Qualifier("patientJdbcTemplate") JdbcTemplate patientJdbc;
    @Autowired @Qualifier("patientTransactionManager") PlatformTransactionManager patientTx;
    @Autowired TenantAuthorization authorization;
    @Autowired PatientSqlScope patientScope;

    record Fixture(UUID tenant, UUID membership, UUID branch, UUID otherBranch,
                   UUID user, UUID session, UUID role) {}

    @BeforeAll
    void installCandidateInDisposablePostgresOnly() throws Exception {
        // Candidate SQL is intentionally outside Flyway's main migration path.
        // Explicitly authorize applying it ONLY to this freshly created container.
        Path base=Path.of(System.getProperty("basedir",System.getProperty("user.dir")))
                .toAbsolutePath();
        Path candidate=base.resolve("../deploy/registration/review-only/" +
                "V10__approved_clinic_registration_writer.sql").normalize();
        assertThat(Files.isRegularFile(candidate))
                .as("Review candidate must exist outside production Flyway path").isTrue();
        try(Connection owner=dataSource.getConnection()) {
            owner.setAutoCommit(false);
            try {
                ScriptUtils.executeSqlScript(owner,
                        new EncodedResource(new FileSystemResource(candidate.toFile())),
                        false, false, ScriptUtils.DEFAULT_COMMENT_PREFIX,
                        ScriptUtils.EOF_STATEMENT_SEPARATOR,
                        ScriptUtils.DEFAULT_BLOCK_COMMENT_START_DELIMITER,
                        ScriptUtils.DEFAULT_BLOCK_COMMENT_END_DELIMITER);
                RegistrationDatabaseProvisioner.installProofKey(owner,1,SIGNING_KEY);
                owner.commit();
            } catch (Exception failure) {
                owner.rollback();
                throw failure;
            }
        }
    }

    @AfterEach
    void clearPrincipal() { SecurityContextHolder.clearContext(); }

    private Fixture fixture() {
        UUID tenant=UUID.randomUUID(),member=UUID.randomUUID(),user=UUID.randomUUID(),
                facility=UUID.randomUUID(),branch=UUID.randomUUID(),
                otherBranch=UUID.randomUUID(),role=UUID.randomUUID(),session=UUID.randomUUID();
        String suffix=UUID.randomUUID().toString().substring(0,8);
        admin.update("INSERT INTO tenant(id,code,display_name) VALUES (?,?,?)",
                tenant,"test_"+suffix,"Fictional test facility");
        admin.update("""
                INSERT INTO app_user(id,username,password_hash,must_change_password)
                VALUES (?,?,?,false)
                """,user,"synthetic_"+suffix,"not-an-actual-credential");
        admin.update("INSERT INTO user_tenant_membership(id,user_id,tenant_id) VALUES (?,?,?)",
                member,user,tenant);
        admin.update("""
                INSERT INTO facility(id,tenant_id,code,display_name,facility_kind)
                VALUES (?,?,?,'Synthetic facility','CLINIC')
                """,facility,tenant,"f_"+suffix);
        admin.update("""
                INSERT INTO branch(id,tenant_id,facility_id,code,display_name)
                VALUES (?,?,?,'Synthetic branch')
                """,branch,tenant,facility,"b_"+suffix);
        admin.update("""
                INSERT INTO branch(id,tenant_id,facility_id,code,display_name)
                VALUES (?,?,?,'Other synthetic branch')
                """,otherBranch,tenant,facility,"o_"+suffix);
        admin.update("""
                INSERT INTO org_membership_branch_scope(tenant_id,membership_id,branch_id)
                VALUES (?,?,?)
                """,tenant,member,branch);
        admin.update("""
                INSERT INTO auth_role(id,tenant_id,code,display_name)
                VALUES (?,?,?,'RECEPTION','Synthetic reception')
                """,role,tenant);
        admin.update("""
                INSERT INTO auth_role_permission(tenant_id,role_id,permission_code)
                VALUES (?,?,'PATIENT_REGISTER')
                """,tenant,role);
        admin.update("""
                INSERT INTO auth_membership_role(tenant_id,membership_id,role_id)
                VALUES (?,?,?)
                """,tenant,member,role);
        // Test fixture only: V9's unreleased module is unchanged for real clinics.
        admin.update("""
                UPDATE module_registry SET implemented=true,released=true
                WHERE code='PATIENT_REGISTRATION'
                """);
        admin.update("""
                INSERT INTO tenant_module_state
                    (tenant_id,module_code,entitled,activated,clinically_authorized)
                VALUES (?,'PATIENT_REGISTRATION',true,true,true)
                """,tenant);
        admin.update("""
                INSERT INTO auth_session(id,membership_id,tenant_id,token_sha256,expires_at)
                VALUES (?,?,?,?,now()+interval '30 minutes')
                """,session,member,tenant,UUID.randomUUID().toString().replace("-","").repeat(2));
        return new Fixture(tenant,member,branch,otherBranch,user,session,role);
    }

    private void as(Fixture f, String... permissions) {
        var principal=new AuthPrincipal(f.user(),f.tenant(),f.membership(),
                "synthetic",false,List.of("RECEPTION"),List.of(permissions),f.session());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal,null,List.of()));
    }

    private RegistrationWriteExecutor executor() {
        return new RegistrationWriteExecutor(authorization,patientScope,patientJdbc,
                patientTx,IDEMPOTENCY_SECRET,SIGNING_KEY,1,Clock.systemUTC());
    }

    private static PatientRegistrationRules.Intake intake(String name) {
        return new PatientRegistrationRules.Intake(name,
                PatientRegistrationRules.BirthDatePrecision.UNKNOWN,null);
    }
    private static String key() {
        return UUID.randomUUID().toString().replace("-","") +
                UUID.randomUUID().toString().replace("-","");
    }

    @Test
    void candidateCommitsOnePatientMrnRegistrationAndImmutableActorAudit() {
        Fixture f=fixture();
        as(f,"PATIENT_REGISTER");
        String key=key();
        var writer=executor();
        var first=writer.register(f.branch(),intake("Fictional Candidate One"),key);
        assertThat(first.replayed()).isFalse();
        assertThat(first.mrn()).matches("PAT-[0-9A-HJKMNP-TV-Z]{12}");
        var replay=writer.register(f.branch(),intake("Fictional Candidate One"),key);
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.patientId()).isEqualTo(first.patientId());
        assertThat(replay.registrationId()).isEqualTo(first.registrationId());
        assertThat(replay.mrn()).isEqualTo(first.mrn());
        for(String relation:List.of("patient","patient_identifier","patient_registration",
                "patient_registration_idempotency","patient_registration_write_audit")) {
            assertThat(admin.queryForObject(
                    "SELECT count(*) FROM public."+relation+" WHERE tenant_id=?",
                    Integer.class,f.tenant())).isEqualTo(1);
        }
    }

    @Test
    void candidateDoesNotAutoMergeDuplicateOrAcceptChangedReplay() {
        Fixture f=fixture();
        as(f,"PATIENT_REGISTER");
        var writer=executor();
        String key=key();
        writer.register(f.branch(),intake("Fictional Candidate Duplicate"),key);
        assertThatThrownBy(()->writer.register(
                f.branch(),intake("Fictional Candidate Changed"),key))
                .isInstanceOf(RegistrationWriteExecutor.Rejected.class)
                .hasMessage("IDEMPOTENCY_CONFLICT");
        assertThatThrownBy(()->writer.register(
                f.branch(),intake("Fictional Candidate Duplicate"),key()))
                .isInstanceOf(RegistrationWriteExecutor.Rejected.class)
                .hasMessage("DUPLICATE_REVIEW_REQUIRED");
        assertThat(admin.queryForObject("SELECT count(*) FROM patient WHERE tenant_id=?",
                Integer.class,f.tenant())).isEqualTo(1);
    }

    @Test
    void sqlRechecksRevokedSessionAndBranchEvenWithStaleJavaPrincipal() {
        Fixture f=fixture();
        as(f,"PATIENT_REGISTER");
        var writer=executor();
        assertThatThrownBy(()->writer.register(
                f.otherBranch(),intake("Fictional Wrong Branch"),key()))
                .isInstanceOf(AccessDeniedException.class);
        admin.update("UPDATE auth_session SET revoked_at=now() WHERE id=?",f.session());
        assertThatThrownBy(()->writer.register(
                f.branch(),intake("Fictional Revoked Actor"),key()))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(admin.queryForObject("SELECT count(*) FROM patient WHERE tenant_id=?",
                Integer.class,f.tenant())).isZero();
    }

    @Test
    void sqlRevokedRolePermissionCannotBeBypassedByInMemoryJavaPermission() {
        Fixture f=fixture();
        as(f,"PATIENT_REGISTER");
        admin.update("""
                DELETE FROM auth_role_permission
                WHERE tenant_id=? AND role_id=? AND permission_code='PATIENT_REGISTER'
                """,f.tenant(),f.role());
        assertThatThrownBy(()->executor().register(
                f.branch(),intake("Fictional Revoked Role"),key()))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(admin.queryForObject("SELECT count(*) FROM patient WHERE tenant_id=?",
                Integer.class,f.tenant())).isZero();
    }

    @Test
    void patientRuntimeNeverGetsDirectWriteDespiteFunctionExecution() throws Exception {
        try(Connection c=DriverManager.getConnection(postgres.getJdbcUrl(),
                "aronexa_patient_runtime","only-for-ephemeral-patient-role")) {
            try(var s=c.createStatement();var rs=s.executeQuery("""
                    SELECT has_table_privilege(current_user,'public.patient','INSERT')
                        OR has_table_privilege(current_user,'public.patient_identifier','INSERT')
                        OR has_table_privilege(current_user,'public.patient_registration','INSERT')
                    """)) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getBoolean(1)).isFalse();
            }
        }
    }

    @Test
    void installingCandidateFromReviewFolderNeverApprovesProductionReadiness() throws Exception {
        try(Connection c=DriverManager.getConnection(postgres.getJdbcUrl(),
                postgres.getUsername(),postgres.getPassword())) {
            c.setReadOnly(true);c.setAutoCommit(false);
            var status=RegistrationWritePreflight.inspect(c,1);
            assertThat(status.ready()).isFalse();
            assertThat(status.findings()).contains(
                    RegistrationWritePreflight.Finding.APPROVED_FLYWAY_MIGRATION_MISSING);
            // All structural checks should pass on the isolated SQL candidate;
            // a missing APPROVED Flyway migration remains a hard release block.
            assertThat(status.findings()).doesNotContain(
                    RegistrationWritePreflight.Finding.REGISTRATION_FUNCTION_MISSING_OR_UNSAFE,
                    RegistrationWritePreflight.Finding.RESTRICTED_WRITER_ROLE_MISSING_OR_UNSAFE,
                    RegistrationWritePreflight.Finding.PATIENT_RUNTIME_HAS_DIRECT_IDENTITY_WRITE,
                    RegistrationWritePreflight.Finding.PATIENT_RUNTIME_FUNCTION_EXECUTE_MISSING,
                    RegistrationWritePreflight.Finding.PROOF_KEY_STORE_NOT_READY,
                    RegistrationWritePreflight.Finding.REGISTRATION_AUDIT_NOT_READY,
                    RegistrationWritePreflight.Finding.PATIENT_RLS_NOT_ENFORCED,
                    RegistrationWritePreflight.Finding.DATABASE_PREFLIGHT_FAILED);
            c.rollback();
        }
    }
}
