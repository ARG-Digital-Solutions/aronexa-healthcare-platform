package com.argdigital.aronexa.auth;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Establish the verified tenant for RLS on the *current* transaction connection.
 *
 * A future tenant-owned service must first authorize the resource, begin a
 * Spring transaction, then call this on the SAME datasource before querying.
 * Never pass a tenant selected from an HTTP header/body/URL into this helper.
 */
@Service
public class TenantSqlScope {
    private final TenantAuthorization authorization;
    private final JdbcTemplate jdbc;

    TenantSqlScope(TenantAuthorization authorization, JdbcTemplate jdbc) {
        this.authorization = authorization;
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void bindVerifiedTenant() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("An active DB transaction is required for RLS");
        }
        AuthPrincipal identity = authorization.currentPrincipal();
        if (identity.mustChangePassword()) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "Password update required before tenant-owned data access");
        }
        jdbc.queryForObject("SELECT set_config('aronexa.tenant_id', ?, true)",
                String.class, identity.tenantId().toString());
    }
}
