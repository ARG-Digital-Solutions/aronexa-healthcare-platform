package com.argdigital.aronexa.patient;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Read-only, fail-closed installer preflight for a FUTURE production registration
 * writer. It never grants roles, creates tables, installs keys or activates a
 * module. Not a Spring bean and never executed on ordinary application startup.
 *
 * The current V1-V9 application and synthetic C2 test writer MUST report BLOCKED.
 * A later independently reviewed, immutable V10 migration must install the
 * separately named production writer before READY can ever be returned.
 *
 * This is a structural precondition only; a READY result would NOT replace
 * authorization tests, migration rehearsal, key escrow or clinical signoff.
 */
public final class RegistrationWritePreflight {
    private RegistrationWritePreflight() {}

    public enum Finding {
        EXPLICIT_READ_ONLY_TRANSACTION_REQUIRED,
        APPROVED_FLYWAY_MIGRATION_MISSING,
        RESTRICTED_WRITER_ROLE_MISSING_OR_UNSAFE,
        REGISTRATION_FUNCTION_MISSING_OR_UNSAFE,
        PATIENT_RUNTIME_HAS_DIRECT_IDENTITY_WRITE,
        PATIENT_RUNTIME_FUNCTION_EXECUTE_MISSING,
        PROOF_KEY_STORE_NOT_READY,
        REGISTRATION_AUDIT_NOT_READY,
        PATIENT_RLS_NOT_ENFORCED,
        DATABASE_PREFLIGHT_FAILED
    }

    public record Result(boolean ready, List<Finding> findings) {
        public Result {
            findings = List.copyOf(findings);
            if (ready != findings.isEmpty()) {
                throw new IllegalArgumentException("Inconsistent preflight result");
            }
        }
    }

    /**
     * Only constant, SELECT-only PostgreSQL catalog queries. Never returns
     * SQL exception messages, key bytes, patient rows or connection credentials.
     */
    public static Result inspect(Connection connection, int proofKeyId) {
        if (connection == null || proofKeyId < 1) {
            return blocked(Finding.DATABASE_PREFLIGHT_FAILED);
        }
        try {
            if (connection.getAutoCommit() || !connection.isReadOnly()) {
                return blocked(Finding.EXPLICIT_READ_ONLY_TRANSACTION_REQUIRED);
            }
            List<Finding> findings = new ArrayList<>();

            if (!bool(connection, """
                    SELECT EXISTS (
                      SELECT 1 FROM public.flyway_schema_history
                      WHERE version='10'
                        AND description='approved clinic registration writer'
                        AND success)
                    """)) {
                findings.add(Finding.APPROVED_FLYWAY_MIGRATION_MISSING);
            }

            boolean writer = bool(connection, """
                    SELECT EXISTS (
                      SELECT 1 FROM pg_catalog.pg_roles r
                      WHERE r.rolname='aronexa_registration_writer'
                        AND NOT r.rolcanlogin AND NOT r.rolsuper
                        AND NOT r.rolbypassrls AND NOT r.rolcreatedb
                        AND NOT r.rolcreaterole AND NOT r.rolreplication
                        AND NOT r.rolinherit
                        AND NOT has_schema_privilege(
                          'aronexa_registration_writer','public','CREATE')
                        AND NOT EXISTS (
                          SELECT 1 FROM pg_catalog.pg_auth_members m
                          WHERE m.roleid=r.oid OR m.member=r.oid))
                    """);
            if (!writer) {
                findings.add(Finding.RESTRICTED_WRITER_ROLE_MISSING_OR_UNSAFE);
            }

            // Approved production function MUST be distinct from the
            // synthetic-only aronexa_c2_register_patient function.
            boolean function = bool(connection, """
                    SELECT EXISTS (
                      SELECT 1 FROM pg_catalog.pg_proc p
                      JOIN pg_catalog.pg_namespace n ON n.oid=p.pronamespace
                      JOIN pg_catalog.pg_roles r ON r.oid=p.proowner
                      WHERE n.nspname='public'
                        AND p.proname='aronexa_register_patient'
                        AND p.prosecdef AND p.provolatile='v'
                        AND r.rolname='aronexa_registration_writer'
                        AND p.oid = pg_catalog.to_regprocedure(
                          'public.aronexa_register_patient(uuid,uuid,uuid,uuid,text,text,date,text,text,text,integer,uuid,bigint,text)')
                        AND NOT EXISTS (
                          SELECT 1 FROM pg_catalog.aclexplode(
                              COALESCE(p.proacl,
                                  pg_catalog.acldefault('f',p.proowner))) acl
                          WHERE acl.grantee=0 AND acl.privilege_type='EXECUTE'
                        )
                      )
                    """);
            if (!function) {
                findings.add(Finding.REGISTRATION_FUNCTION_MISSING_OR_UNSAFE);
            }

            boolean patientRole = bool(connection, """
                    SELECT EXISTS (SELECT 1 FROM pg_catalog.pg_roles
                      WHERE rolname='aronexa_patient_runtime'
                        AND rolcanlogin AND NOT rolsuper AND NOT rolbypassrls
                        AND NOT rolcreatedb AND NOT rolcreaterole)
                    """);
            if (!patientRole) {
                findings.add(Finding.PATIENT_RUNTIME_HAS_DIRECT_IDENTITY_WRITE);
            } else {
                boolean directWrite = bool(connection, """
                        SELECT has_table_privilege('aronexa_patient_runtime',
                          'public.patient','INSERT')
                          OR has_table_privilege('aronexa_patient_runtime',
                          'public.patient_identifier','INSERT')
                          OR has_table_privilege('aronexa_patient_runtime',
                          'public.patient_registration','INSERT')
                        """);
                if (regclass(connection, "public.patient_registration_idempotency")) {
                    directWrite |= bool(connection, """
                            SELECT has_table_privilege('aronexa_patient_runtime',
                              'public.patient_registration_idempotency','SELECT')
                              OR has_table_privilege('aronexa_patient_runtime',
                              'public.patient_registration_idempotency','INSERT')
                            """);
                }
                if (regclass(connection, "public.patient_registration_proof_key")) {
                    directWrite |= bool(connection, """
                            SELECT has_table_privilege('aronexa_patient_runtime',
                              'public.patient_registration_proof_key','SELECT')
                            """);
                }
                if (directWrite) {
                    findings.add(Finding.PATIENT_RUNTIME_HAS_DIRECT_IDENTITY_WRITE);
                }
                if (function) {
                    if (!bool(connection, """
                            SELECT has_function_privilege('aronexa_patient_runtime',
                              'public.aronexa_register_patient(uuid,uuid,uuid,uuid,text,text,date,text,text,text,integer,uuid,bigint,text)',
                              'EXECUTE')
                            """)) {
                        findings.add(Finding.PATIENT_RUNTIME_FUNCTION_EXECUTE_MISSING);
                    }
                }
            }

            boolean keysExist = regclass(connection,
                    "public.patient_registration_proof_key");
            if (!keysExist || !activeKey(connection, proofKeyId)) {
                findings.add(Finding.PROOF_KEY_STORE_NOT_READY);
            }

            if (!regclass(connection, "public.patient_registration_write_audit")
                    || !bool(connection, """
                      SELECT EXISTS (
                        SELECT 1 FROM pg_catalog.pg_trigger t
                        WHERE t.tgrelid='public.patient_registration_write_audit'::regclass
                          AND t.tgname='patient_registration_write_audit_immutable'
                          AND NOT t.tgisinternal AND t.tgenabled <> 'D')
                      """)) {
                findings.add(Finding.REGISTRATION_AUDIT_NOT_READY);
            }
            if (!bool(connection, """
                    SELECT COUNT(*)=2 FROM pg_catalog.pg_class
                    WHERE oid IN ('public.patient'::regclass,
                                  'public.patient_registration'::regclass)
                      AND relrowsecurity AND relforcerowsecurity
                    """)) {
                findings.add(Finding.PATIENT_RLS_NOT_ENFORCED);
            }

            return new Result(findings.isEmpty(), findings);
        } catch (SQLException | RuntimeException failure) {
            // Fail closed on catalog lookup/permissions, malformed schema,
            // unexpected driver exceptions or missing underlying relations.
            return blocked(Finding.DATABASE_PREFLIGHT_FAILED);
        }
    }

    private static boolean regclass(Connection c, String relation) throws SQLException {
        // Only fixed source-controlled names ever reach the catalog query.
        try (PreparedStatement s = c.prepareStatement(
                "SELECT pg_catalog.to_regclass(?) IS NOT NULL")) {
            s.setString(1, relation);
            try (ResultSet rs = s.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        }
    }

    private static boolean activeKey(Connection c, int keyId) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("""
                SELECT EXISTS (
                    SELECT 1 FROM public.patient_registration_proof_key
                    WHERE id=? AND enabled
                      AND valid_from<=CURRENT_TIMESTAMP
                      AND valid_until>CURRENT_TIMESTAMP)
                """)) {
            s.setInt(1, keyId);
            try (ResultSet rs = s.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        }
    }

    private static boolean bool(Connection c, String sql) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(sql);
             ResultSet rs = s.executeQuery()) {
            return rs.next() && rs.getBoolean(1);
        }
    }

    private static Result blocked(Finding code) {
        return new Result(false, List.of(code));
    }
}
