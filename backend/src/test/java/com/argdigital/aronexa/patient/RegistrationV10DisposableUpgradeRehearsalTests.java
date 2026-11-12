package com.argdigital.aronexa.patient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R1-01: simulated V9 -> review-only V10 upgrade and V9 restore on an
 * automatically destroyed PostgreSQL 17 Testcontainer. No customer, Windows,
 * developer or remotely configured database can be selected by this class.
 *
 * This is NOT an approved Flyway V10, installer, production backup or migration
 * authorization. A real migration still requires independent security review.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class RegistrationV10DisposableUpgradeRehearsalTests {
    private static final String DB = "aronexa_disposable_v10_rehearsal";
    private static final String RESTORE_DB = "aronexa_disposable_v9_restored";
    private static final String DUMP = "/tmp/aronexa-v9-synthetic-only.dump";

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine")
            .withInitScript("db/test/role-bootstrap.sql")
            .withDatabaseName(DB)
            .withUsername(DB)
            .withPassword("ephemeral-rehearsal-only");

    @Autowired DataSource ownerDataSource;
    @Autowired JdbcTemplate admin;
    @Autowired Flyway flyway;

    record Existing(UUID tenant, UUID patient, UUID registration, String mrn, int auditCount) {}

    private static void requireDisposableContainer() {
        assertThat(postgres.isRunning()).as("Testcontainers must own running PostgreSQL").isTrue();
        assertThat(postgres.getDatabaseName()).isEqualTo(DB);
        assertThat(postgres.getUsername()).isEqualTo(DB);
        assertThat(postgres.getDockerImageName()).startsWith("postgres:17-");
    }

    private static void runPostgresTool(String... args) throws Exception {
        requireDisposableContainer();
        String[] command=new String[args.length+2];
        command[0]="env";
        // Deliberately ephemeral, hard-coded Testcontainers password only.
        command[1]="PGPASSWORD="+postgres.getPassword();
        System.arraycopy(args,0,command,2,args.length);
        var result=postgres.execInContainer(command);
        // Keep internal pg_dump output out of logs; a failure terminates the test.
        assertThat(result.getExitCode())
                .as("Disposable PostgreSQL backup/restore command must succeed").isZero();
    }

    private static Path candidateFile() {
        Path basedir=Path.of(System.getProperty("basedir",
                System.getProperty("user.dir"))).toAbsolutePath();
        Path file=basedir.resolve("../deploy/registration/review-only/"
                +"V10__approved_clinic_registration_writer.sql").normalize();
        assertThat(Files.isRegularFile(file))
                .as("The review candidate must be outside normal Flyway locations").isTrue();
        assertThat(new ClassPathResource("db/migration/"
                +"V10__approved_clinic_registration_writer.sql").exists())
                .as("Production migration must NOT be promoted by R1-01").isFalse();
        return file;
    }

    private static void executeCandidate(Connection c, Path file) throws Exception {
        assertThat(c.getAutoCommit()).as("Candidate execution must be transactional").isFalse();
        ScriptUtils.executeSqlScript(c,
                new EncodedResource(new FileSystemResource(file.toFile())),
                false,false,ScriptUtils.DEFAULT_COMMENT_PREFIX,
                ScriptUtils.EOF_STATEMENT_SEPARATOR,
                ScriptUtils.DEFAULT_BLOCK_COMMENT_START_DELIMITER,
                ScriptUtils.DEFAULT_BLOCK_COMMENT_END_DELIMITER);
    }

    private static boolean exists(Connection c, String qualified) throws Exception {
        try(var st=c.prepareStatement("SELECT pg_catalog.to_regclass(?) IS NOT NULL")) {
            st.setString(1,qualified);
            try(var result=st.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getBoolean(1);
            }
        }
    }

    private static int scalar(Connection c, String sql, Object... params) throws Exception {
        try(var query=c.prepareStatement(sql)) {
            for(int i=0;i<params.length;i++) query.setObject(i+1,params[i]);
            try(var results=query.executeQuery()) {
                assertThat(results.next()).isTrue();
                return results.getInt(1);
            }
        }
    }

    private static String value(Connection c, String sql, Object... params) throws Exception {
        try(var query=c.prepareStatement(sql)) {
            for(int i=0;i<params.length;i++) query.setObject(i+1,params[i]);
            try(var results=query.executeQuery()) {
                assertThat(results.next()).isTrue();
                return results.getString(1);
            }
        }
    }

    private Existing seedExistingV9Identity() {
        UUID tenant=UUID.randomUUID(), facility=UUID.randomUUID(), branch=UUID.randomUUID(),
                user=UUID.randomUUID(), membership=UUID.randomUUID(),
                patient=UUID.randomUUID(), identifier=UUID.randomUUID(),
                registration=UUID.randomUUID();
        String mrn="PAT-TESTV9-001";
        admin.update("INSERT INTO tenant(id,code,display_name) VALUES(?,?,?)",
                tenant,"r1_"+tenant.toString().substring(0,8),"FICTIONAL V9 TENANT");
        admin.update("INSERT INTO facility(id,tenant_id,code,display_name,facility_kind) "
                +"VALUES(?,?,?,'Disposable facility','CLINIC')",
                facility,tenant,"f_"+facility.toString().substring(0,8));
        admin.update("INSERT INTO branch(id,tenant_id,facility_id,code,display_name) "
                +"VALUES(?,?,?,?,'Disposable branch')",
                branch,tenant,facility,"b_"+branch.toString().substring(0,8));
        admin.update("INSERT INTO app_user(id,username,password_hash) VALUES (?,?,?)",
                user,"r1_"+user.toString().substring(0,10),"no-real-credential");
        admin.update("INSERT INTO user_tenant_membership(id,user_id,tenant_id) VALUES(?,?,?)",
                membership,user,tenant);
        admin.update("INSERT INTO patient(id,tenant_id,display_name) VALUES(?,?,?)",
                patient,tenant,"FICTIONAL V9 PRESERVED PATIENT");
        admin.update("INSERT INTO patient_identifier(id,tenant_id,patient_id,identifier_type,identifier_value) "
                +"VALUES(?,?,?,'MRN',?)",identifier,tenant,patient,mrn);
        admin.update("INSERT INTO patient_registration(id,tenant_id,patient_id,branch_id,"
                +"registration_source,registered_by_membership_id) "
                +"VALUES(?,?,?,?,'RECEPTION',?)",
                registration,tenant,patient,branch,membership);
        int audit=admin.queryForObject("SELECT count(*) FROM patient_identity_audit "
                +"WHERE tenant_id=? AND patient_id=?",Integer.class,tenant,patient);
        assertThat(audit).isPositive();
        return new Existing(tenant,patient,registration,mrn,audit);
    }

    private void assertExistingIdentity(Connection c, Existing f) throws Exception {
        assertThat(scalar(c,"SELECT count(*) FROM public.patient "
                +"WHERE tenant_id=? AND id=? AND display_name='FICTIONAL V9 PRESERVED PATIENT'",
                f.tenant(),f.patient())).isOne();
        assertThat(value(c,"SELECT identifier_value FROM public.patient_identifier "
                +"WHERE tenant_id=? AND patient_id=? AND identifier_type='MRN'",
                f.tenant(),f.patient())).isEqualTo(f.mrn());
        assertThat(scalar(c,"SELECT count(*) FROM public.patient_registration "
                +"WHERE tenant_id=? AND id=? AND patient_id=?",
                f.tenant(),f.registration(),f.patient())).isOne();
        assertThat(scalar(c,"SELECT count(*) FROM public.patient_identity_audit "
                +"WHERE tenant_id=? AND patient_id=?",
                f.tenant(),f.patient())).isEqualTo(f.auditCount());
    }

    private void assertV9State(Connection c) throws Exception {
        assertThat(value(c,"SELECT version FROM public.flyway_schema_history "
                +"WHERE success ORDER BY installed_rank DESC LIMIT 1"))
                .isEqualTo("9");
        assertThat(exists(c,"public.patient_registration_proof_key")).isFalse();
        assertThat(exists(c,"public.patient_registration_idempotency")).isFalse();
        assertThat(exists(c,"public.patient_registration_write_audit")).isFalse();
        assertThat(scalar(c,"SELECT count(*) FROM public.module_registry "
                +"WHERE code='PATIENT_REGISTRATION' AND NOT implemented AND NOT released"))
                .isOne();
        assertThat(scalar(c,"SELECT count(*) FROM pg_catalog.pg_class "
                +"WHERE oid IN ('public.patient'::regclass,"
                +"'public.patient_registration'::regclass) "
                +"AND relrowsecurity AND relforcerowsecurity")).isEqualTo(2);
    }

    private static Connection restoredConnection() throws Exception {
        requireDisposableContainer();
        String jdbc=postgres.getJdbcUrl().replace("/"+DB, "/"+RESTORE_DB);
        assertThat(jdbc).contains("/"+RESTORE_DB);
        return DriverManager.getConnection(jdbc,
                postgres.getUsername(),postgres.getPassword());
    }

    @Test
    void v9ExistingIdentityRollbackUpgradeAndIndependentV9Restore() throws Exception {
        requireDisposableContainer();
        assertThat(flyway.info().current()).isNotNull();
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("9");
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
        Path candidate=candidateFile();
        Existing existing=seedExistingV9Identity();

        try(Connection v9=ownerDataSource.getConnection()) {
            assertV9State(v9);
            assertExistingIdentity(v9,existing);
        }

        // Dump a populated V9 schema and records before any proposed DDL.
        // Output remains inside the disposable container (not a real backup).
        runPostgresTool("pg_dump","--host=127.0.0.1","--username="+DB,
                "--dbname="+DB,"--format=custom","--file="+DUMP);
        runPostgresTool("pg_restore","--list",DUMP);

        // Exercise complete proposed DDL, then rollback, proving atomic
        // abandonment leaves the existing V9 database unchanged.
        try(Connection tx=ownerDataSource.getConnection()) {
            tx.setAutoCommit(false);
            try {
                executeCandidate(tx,candidate);
                assertThat(exists(tx,"public.patient_registration_idempotency")).isTrue();
            } finally {
                tx.rollback();
            }
        }
        try(Connection v9=ownerDataSource.getConnection()) {
            assertV9State(v9);
            assertExistingIdentity(v9,existing);
        }

        // Upgrade rehearsal only: no Flyway V10 entry or secret provisioning.
        try(Connection tx=ownerDataSource.getConnection()) {
            tx.setAutoCommit(false);
            try {
                executeCandidate(tx,candidate);
                tx.commit();
            } catch(Exception failure) {
                tx.rollback();
                throw failure;
            }
        }

        try(Connection reviewed=ownerDataSource.getConnection()) {
            assertExistingIdentity(reviewed,existing);
            assertThat(exists(reviewed,"public.patient_registration_idempotency")).isTrue();
            assertThat(exists(reviewed,"public.patient_registration_write_audit")).isTrue();
            assertThat(exists(reviewed,"public.patient_registration_proof_key")).isTrue();
            assertThat(scalar(reviewed,
                    "SELECT count(*) FROM pg_catalog.pg_proc p "
                    +"JOIN pg_catalog.pg_namespace n ON n.oid=p.pronamespace "
                    +"JOIN pg_catalog.pg_roles r ON r.oid=p.proowner "
                    +"WHERE n.nspname='public' AND p.proname='aronexa_register_patient' "
                    +"AND p.prosecdef AND r.rolname='aronexa_registration_writer' "
                    +"AND NOT r.rolcanlogin AND NOT r.rolsuper AND NOT r.rolbypassrls"))
                    .as("Candidate SECURITY DEFINER owned by restricted NOLOGIN role")
                    .isOne();
            assertThat(scalar(reviewed,
                    "SELECT count(*) FROM pg_catalog.pg_class "
                    +"WHERE oid IN ('public.patient_registration_idempotency'::regclass,"
                    +"'public.patient_registration_write_audit'::regclass) "
                    +"AND relrowsecurity AND relforcerowsecurity")).isEqualTo(2);
            assertThat(scalar(reviewed,
                    "SELECT CASE WHEN has_table_privilege('aronexa_patient_runtime',"
                    +"'public.patient','INSERT') OR has_table_privilege("
                    +"'aronexa_patient_runtime','public.patient_registration','INSERT') "
                    +"THEN 1 ELSE 0 END")).isZero();
            assertThat(value(reviewed,"SELECT version FROM public.flyway_schema_history "
                    +"WHERE success ORDER BY installed_rank DESC LIMIT 1")).isEqualTo("9");
            // In particular, no deployment approval follows merely from
            // installing review-only SQL with a privileged test migrator.
            reviewed.setReadOnly(true);
            reviewed.setAutoCommit(false);
            var preflight=RegistrationWritePreflight.inspect(reviewed,1);
            assertThat(preflight.ready()).isFalse();
            assertThat(preflight.findings()).contains(
                    RegistrationWritePreflight.Finding.APPROVED_FLYWAY_MIGRATION_MISSING,
                    RegistrationWritePreflight.Finding.PROOF_KEY_STORE_NOT_READY);
            reviewed.rollback();
        }

        // Restore the original V9 snapshot into a second disposable database
        // inside this same Testcontainer; never overwrite the rehearsal DB.
        runPostgresTool("createdb","--host=127.0.0.1","--username="+DB,
                "--owner="+DB,RESTORE_DB);
        runPostgresTool("pg_restore","--host=127.0.0.1","--username="+DB,
                "--dbname="+RESTORE_DB,"--clean","--if-exists",
                "--exit-on-error",DUMP);
        try(Connection restored=restoredConnection()) {
            assertV9State(restored);
            assertExistingIdentity(restored,existing);
            assertThat(scalar(restored,
                    "SELECT count(*) FROM pg_catalog.pg_roles "
                    +"WHERE rolname='aronexa_registration_writer' "
                    +"AND NOT rolcanlogin AND NOT rolsuper AND NOT rolbypassrls")).isOne();
            assertThat(scalar(restored,
                    "SELECT count(*) FROM pg_catalog.pg_class "
                    +"WHERE oid='public.patient'::regclass AND relforcerowsecurity"))
                    .isOne();
            restored.setReadOnly(true);
            restored.setAutoCommit(false);
            var recoveredPreflight=RegistrationWritePreflight.inspect(restored,1);
            assertThat(recoveredPreflight.ready()).isFalse();
            restored.rollback();
        }
    }
}
