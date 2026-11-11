package com.argdigital.aronexa.patient;

import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Arrays;

/**
 * Explicit installer primitive, never a Spring bean, HTTP route or startup hook.
 * Caller supplies a privileged installation connection and owns commit/rollback.
 * Never call with a runtime pool. No grant to runtime logins, migration execution,
 * key generation/rotation, filesystem persistence or module activation occurs.
 */
public final class RegistrationDatabaseProvisioner {
    private RegistrationDatabaseProvisioner() {}

    private static void requireTransaction(Connection connection) throws SQLException {
        if (connection == null || connection.getAutoCommit()) {
            throw new IllegalArgumentException("Explicit provisioning transaction required");
        }
    }

    /** Returns true when created, false when an existing safe role is preserved. */
    public static boolean ensureRestrictedWriter(Connection connection) throws SQLException {
        requireTransaction(connection);
        try {
            // Serialize this installer's operations within a database. PostgreSQL
            // still rejects a conflicting cluster-wide role creation elsewhere.
            try(var lock=connection.createStatement()) {
                lock.execute("SELECT pg_catalog.pg_advisory_xact_lock(17492, 1)");
            }
            boolean created=false;
            try(var query=connection.createStatement();var row=query.executeQuery(
                    "SELECT 1 FROM pg_catalog.pg_roles WHERE rolname='aronexa_registration_writer'")) {
                if(!row.next()) created=true;
            }
            if(created) try(var create=connection.createStatement()) {
                create.execute("CREATE ROLE aronexa_registration_writer NOLOGIN NOSUPERUSER "+
                        "NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS");
            }
            try(var query=connection.createStatement();var row=query.executeQuery("""
                    SELECT NOT r.rolcanlogin AND NOT r.rolsuper AND NOT r.rolcreatedb
                       AND NOT r.rolcreaterole AND NOT r.rolinherit
                       AND NOT r.rolreplication AND NOT r.rolbypassrls
                       AND NOT EXISTS (SELECT 1 FROM pg_catalog.pg_auth_members m
                                       WHERE m.roleid=r.oid OR m.member=r.oid)
                    FROM pg_catalog.pg_roles r WHERE r.rolname='aronexa_registration_writer'
                    """)) {
                if(!row.next() || !row.getBoolean(1)) {
                    throw new IllegalStateException("REGISTRATION_WRITER_UNSAFE");
                }
            }
            return created;
        } catch(SQLException failure) {
            throw sanitized(failure);
        }
    }

    /**
     * Install one 256-bit verification key after the guarded registration schema
     * exists. Reruns accept only the identical active key. They never overwrite,
     * rotate or re-enable a key. Secure key storage and backups belong to the
     * installer; this method retains no caller-supplied material after return.
     */
    public static boolean installProofKey(Connection connection, int keyId, byte[] material)
            throws SQLException {
        requireTransaction(connection);
        if(keyId<=0 || material==null || material.length!=32) {
            throw new IllegalArgumentException("Invalid registration key material");
        }
        byte[] key=material.clone();
        try {
            ensureRestrictedWriter(connection);
            // Include non-cooperating administrative INSERT/UPDATE operations in
            // serialization, not just callers using this class's advisory lock.
            try(var lock=connection.createStatement()) {
                lock.execute("LOCK TABLE public.patient_registration_proof_key IN SHARE ROW EXCLUSIVE MODE");
            }
            try(var query=connection.prepareStatement("""
                    SELECT secret, enabled AND valid_from<=CURRENT_TIMESTAMP
                                           AND CURRENT_TIMESTAMP<valid_until AS active
                      FROM public.patient_registration_proof_key WHERE id=? FOR UPDATE
                    """)) {
                query.setInt(1,keyId);
                try(var row=query.executeQuery()) {
                    if(row.next()) {
                        byte[] existing=row.getBytes(1);
                        try {
                            if(!row.getBoolean(2) || existing==null || !MessageDigest.isEqual(existing,key)) {
                                throw new IllegalStateException("REGISTRATION_KEY_CONFLICT");
                            }
                        } finally {
                            if(existing!=null) Arrays.fill(existing,(byte)0);
                        }
                        return false;
                    }
                }
            }
            try(var insert=connection.prepareStatement(
                    "INSERT INTO public.patient_registration_proof_key(id,secret) VALUES (?,?)")) {
                insert.setInt(1,keyId);insert.setBytes(2,key);insert.executeUpdate();
            }
            return true;
        } catch(SQLException failure) {
            throw sanitized(failure);
        } finally {
            Arrays.fill(key,(byte)0);
        }
    }

    private static SQLException sanitized(SQLException failure) {
        // SQLState aids diagnosis without exposing vendor detail, bind values,
        // statement text or an unsafe nested exception to an installation log.
        return new SQLException("Registration database provisioning failed",failure.getSQLState());
    }
}
