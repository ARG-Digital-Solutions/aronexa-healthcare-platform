package com.argdigital.aronexa.patient;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * C2-S2-C8: disposable-Testcontainers-only sample of consistent administrative
 * transaction ordering. This is NOT production administration or a web API.
 * Caller provides connections ONLY to the isolated synthetic migrator role.
 */
final class SyntheticAdminBatchExecutor {
    private static final int MAX_ATTEMPTS = 3;

    @FunctionalInterface
    interface ConnectionFactory {
        Connection open() throws SQLException;
    }

    @FunctionalInterface
    interface BeforeCommit {
        void apply(Connection connection, int attempt) throws SQLException;
    }

    record Outcome(int attempts, int changedTenantRows) {}

    private SyntheticAdminBatchExecutor() {}

    /**
     * Atomically deactivate PATIENT_REGISTRATION for a fixed, sorted tenant set.
     * Retry only real PostgreSQL deadlock SQLSTATE 40P01 from a FRESH connection
     * after a complete rollback, with at most three attempts.
     */
    static Outcome deactivateRegistration(ConnectionFactory connections,
                                          Collection<UUID> tenantIds,
                                          BeforeCommit beforeCommit) throws SQLException {
        Objects.requireNonNull(connections, "connections");
        Objects.requireNonNull(tenantIds, "tenantIds");
        Objects.requireNonNull(beforeCommit, "beforeCommit");
        if (tenantIds.isEmpty() || tenantIds.contains(null)) {
            throw new IllegalArgumentException("Synthetic tenant list must be nonempty and non-null");
        }
        List<UUID> ordered=tenantIds.stream().distinct().sorted().toList();
        for (int attempt=1;attempt<=MAX_ATTEMPTS;attempt++) {
            try (Connection connection=connections.open()) {
                connection.setAutoCommit(false);
                try {
                    try (Statement timeout=connection.createStatement()) {
                        timeout.execute("SET LOCAL lock_timeout = '2500ms'");
                        timeout.execute("SET LOCAL statement_timeout = '8000ms'");
                    }
                    // Acquire all tenant advisory locks BEFORE acquiring any row
                    // locks. Otherwise BEFORE-row triggers cannot prevent a
                    // reversed update order from deadlocking.
                    try (PreparedStatement lock=connection.prepareStatement("""
                            SELECT pg_advisory_xact_lock(
                                hashtextextended('ARONEXA-C2-AUTHZ-TENANT:' ||
                                    CAST(? AS text), 0))
                            """)) {
                        for (UUID tenant:ordered) {
                            lock.setObject(1,tenant);
                            try (var ignored=lock.executeQuery()) {
                                if (!ignored.next()) throw new SQLException("Synthetic lock unavailable");
                            }
                        }
                    }
                    int affected=0;
                    try (PreparedStatement update=connection.prepareStatement("""
                            UPDATE public.tenant_module_state
                               SET activated=false, updated_at=clock_timestamp()
                             WHERE tenant_id=? AND module_code='PATIENT_REGISTRATION'
                               AND activated=true
                            """)) {
                        for(UUID tenant:ordered) {
                            update.setObject(1,tenant);
                            affected+=update.executeUpdate();
                        }
                    }
                    // Synthetic test hook can produce a genuine database SQLSTATE
                    // to prove rollback and transaction recreation.
                    beforeCommit.apply(connection,attempt);
                    connection.commit();
                    return new Outcome(attempt,affected);
                } catch(SQLException failure) {
                    try {
                        connection.rollback();
                    } catch(SQLException rollbackFailure) {
                        // No replay if database rollback itself is uncertain.
                        throw new SQLException("Synthetic admin rollback unverified",
                                "C8000");
                    }
                    if (!"40P01".equals(failure.getSQLState()) || attempt==MAX_ATTEMPTS) {
                        // Avoid SQL statement text and sensitive internal details.
                        throw new SQLException("Synthetic admin batch unavailable",
                                failure.getSQLState());
                    }
                }
            }
        }
        throw new SQLException("Synthetic admin batch unavailable", "C8000");
    }
}
