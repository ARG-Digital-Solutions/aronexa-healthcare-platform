package com.argdigital.aronexa.patient;

import com.argdigital.aronexa.auth.AuthPrincipal;
import com.argdigital.aronexa.auth.TenantAuthorization;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * B2: bind both tenant AND staff membership on the patient connection only.
 * PostgreSQL GUCs are transaction-scoped and not trusted when read from
 * caller data. The server-supplied validated AuthPrincipal is the source.
 */
@Service
public class PatientSqlScope {
    private final TenantAuthorization authorization;
    private final JdbcTemplate patientJdbc;

    public PatientSqlScope(TenantAuthorization authorization,
                           @Qualifier("patientJdbcTemplate") JdbcTemplate patientJdbc) {
        this.authorization = authorization;
        this.patientJdbc = patientJdbc;
    }

    @Transactional(transactionManager = "patientTransactionManager",
                   propagation = Propagation.MANDATORY)
    public AuthPrincipal bindVerifiedStaff() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Patient SQL access requires a transaction");
        }
        AuthPrincipal principal = authorization.currentPrincipal();
        if (principal.mustChangePassword()) {
            throw new AccessDeniedException("Patient scope denied");
        }
        patientJdbc.queryForObject(
                "SELECT set_config('aronexa.tenant_id', ?, true)",
                String.class, principal.tenantId().toString());
        patientJdbc.queryForObject(
                "SELECT set_config('aronexa.membership_id', ?, true)",
                String.class, principal.membershipId().toString());
        return principal;
    }
}
