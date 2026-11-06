package com.argdigital.aronexa.patient;

import com.argdigital.aronexa.auth.AuthPrincipal;
import com.argdigital.aronexa.auth.TenantAuthorization;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * B2 internal-only patient registration authorization and restricted read path.
 * No public HTTP route, no patient creation, no demographics/contacts read.
 *
 * The patient account is a separate nonowner SQL role, and each operation
 * binds the trusted principal's tenant+membership on its own patient JDBC
 * transaction. V9 denies writes to patient identity/registration tables.
 */
@Service
public class PatientAccessService {
    static final String REGISTRATION_MODULE = "PATIENT_REGISTRATION";
    static final String REGISTER_PERMISSION = "PATIENT_REGISTER";
    static final String READ_PERMISSION = "PATIENT_REGISTRATION_READ";

    private final TenantAuthorization authorization;
    private final PatientSqlScope sqlScope;
    private final JdbcTemplate patientJdbc;

    public PatientAccessService(TenantAuthorization authorization,
                                PatientSqlScope sqlScope,
                                @Qualifier("patientJdbcTemplate") JdbcTemplate patientJdbc) {
        this.authorization = authorization;
        this.sqlScope = sqlScope;
        this.patientJdbc = patientJdbc;
    }

    /** Design-time B3 precondition only: NOT a transferable authorization token. */
    @Transactional(transactionManager = "patientTransactionManager", readOnly = true)
    public AuthorizedBranch requireReceptionBranch(UUID branchId) {
        AuthPrincipal principal = authorization.requirePermission(REGISTER_PERMISSION);
        if (branchId == null) throw new AccessDeniedException("Patient scope denied");
        sqlScope.bindVerifiedStaff();
        requireEnabledModule(principal);
        if (!assignedBranch(principal, branchId)) {
            throw new AccessDeniedException("Patient scope denied");
        }
        return new AuthorizedBranch(principal.tenantId(), principal.membershipId(), branchId);
    }

    /**
     * Read only the registration/patient UUID mapping after authorization.
     * An actor-attributed append-only audit entry is inserted inside the same
     * patient SQL transaction for successful reads. Unknown/forbidden IDs have
     * the same empty result, and no patient demographics are selected.
     */
    @Transactional(transactionManager = "patientTransactionManager")
    public Optional<AuthorizedRegistration> findReadableRegistration(
            UUID branchId, UUID registrationId) {
        AuthPrincipal principal = authorization.requirePermission(READ_PERMISSION);
        if (branchId == null || registrationId == null) return Optional.empty();
        sqlScope.bindVerifiedStaff();
        requireEnabledModule(principal);

        // Policy on patient_registration also enforces tenant, membership and
        // assigned branch directly in PostgreSQL (not merely this join).
        Optional<AuthorizedRegistration> result = patientJdbc.query("""
                SELECT r.id, r.patient_id
                  FROM public.patient_registration r
                  JOIN public.org_membership_branch_scope s
                    ON s.tenant_id = r.tenant_id
                   AND s.branch_id = r.branch_id
                   AND s.membership_id = ?
                 WHERE r.tenant_id = ?
                   AND r.branch_id = ?
                   AND r.id = ?
                """, (rs, row) -> new AuthorizedRegistration(
                    principal.tenantId(), principal.membershipId(), branchId,
                    rs.getObject("id", UUID.class),
                    rs.getObject("patient_id", UUID.class)),
                principal.membershipId(), principal.tenantId(), branchId, registrationId)
                .stream().findFirst();

        result.ifPresent(found -> patientJdbc.update("""
                INSERT INTO public.patient_registration_access_audit
                    (id,tenant_id,membership_id,branch_id,registration_id,action)
                VALUES (?,?,?,?,?,'READ')
                """, UUID.randomUUID(), principal.tenantId(), principal.membershipId(),
                branchId, found.registrationId()));
        return result;
    }

    private void requireEnabledModule(AuthPrincipal principal) {
        List<Integer> enabled = patientJdbc.queryForList("""
                SELECT 1
                  FROM public.tenant_module_state s
                  JOIN public.module_registry r ON r.code = s.module_code
                 WHERE s.tenant_id = ? AND s.module_code = ?
                   AND r.implemented AND r.released
                   AND s.entitled AND s.activated
                   AND (NOT r.requires_clinical_authorization OR s.clinically_authorized)
                """, Integer.class, principal.tenantId(), REGISTRATION_MODULE);
        if (enabled.isEmpty()) throw new AccessDeniedException("Patient module disabled");
    }

    private boolean assignedBranch(AuthPrincipal principal, UUID branchId) {
        List<Integer> allowed = patientJdbc.queryForList("""
                SELECT 1
                  FROM public.org_membership_branch_scope s
                 WHERE s.tenant_id = ? AND s.membership_id = ? AND s.branch_id = ?
                """, Integer.class, principal.tenantId(), principal.membershipId(), branchId);
        return !allowed.isEmpty();
    }

    /** Trusted internal-only context; not an HTTP DTO or reusable permission. */
    public record AuthorizedBranch(UUID tenantId, UUID membershipId, UUID branchId) {}

    /** No patient demographics, names, DOB, phones or clinical details. */
    public record AuthorizedRegistration(UUID tenantId, UUID membershipId,
                                         UUID branchId, UUID registrationId, UUID patientId) {}
}
