package com.argdigital.aronexa.patient;

import com.argdigital.aronexa.auth.AuthPrincipal;
import com.argdigital.aronexa.auth.TenantAuthorization;
import com.argdigital.aronexa.auth.TenantSqlScope;
import com.argdigital.aronexa.moduleregistry.ModuleAccessService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * P01-T07-B2 internal-only patient authorization boundary.
 *
 * This is NOT a patient API and is NOT a write service. V8 intentionally grants
 * neither runtime account access to patient tables; successful persistence awaits
 * a separately reviewed, forward-only least-privilege migration.
 *
 * Authorization requires the validated staff membership from Spring Security,
 * a precise permission, an enabled dedicated registration module and an assigned
 * branch. A patient registration is visible only at the requested, assigned branch.
 * Unassigned and unknown registration IDs are indistinguishable to callers.
 */
@Service
public class PatientAccessService {
    static final String REGISTRATION_MODULE = "PATIENT_REGISTRATION";
    static final String REGISTER_PERMISSION = "PATIENT_REGISTER";
    static final String READ_PERMISSION = "PATIENT_REGISTRATION_READ";

    private final TenantAuthorization authorization;
    private final TenantSqlScope sqlScope;
    private final ModuleAccessService modules;
    private final JdbcTemplate jdbc;

    public PatientAccessService(TenantAuthorization authorization,
                                TenantSqlScope sqlScope,
                                ModuleAccessService modules,
                                JdbcTemplate jdbc) {
        this.authorization = authorization;
        this.sqlScope = sqlScope;
        this.modules = modules;
        this.jdbc = jdbc;
    }

    /**
     * Internal precondition for a future staff registration transaction.
     * This method alone does NOT authorize an actual patient INSERT.
     *
     * An authorized caller must re-check this scope and the module state
     * inside the eventual write transaction, not reuse an earlier result.
     */
    @Transactional(readOnly = true)
    public AuthorizedBranch requireReceptionBranch(UUID branchId) {
        AuthPrincipal principal = verifiedFor(REGISTER_PERMISSION);
        if (branchId == null) {
            throw new AccessDeniedException("Patient scope denied");
        }
        modules.requireEnabled(REGISTRATION_MODULE);
        sqlScope.bindVerifiedTenant();
        if (!assignedBranch(principal, branchId)) {
            throw new AccessDeniedException("Patient scope denied");
        }
        return new AuthorizedBranch(principal.tenantId(), principal.membershipId(), branchId);
    }

    /**
     * Internal read authorization and minimal patient-registration lookup.
     * Returns IDs only (no clinical or demographic fields).
     * No cross-branch lookup or client-provided tenant selector is accepted.
     */
    @Transactional(readOnly = true)
    public Optional<AuthorizedRegistration> findReadableRegistration(
            UUID branchId, UUID registrationId) {
        AuthPrincipal principal = verifiedFor(READ_PERMISSION);
        if (branchId == null || registrationId == null) {
            return Optional.empty();
        }
        modules.requireEnabled(REGISTRATION_MODULE);
        sqlScope.bindVerifiedTenant();

        return jdbc.query("""
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
    }

    private AuthPrincipal verifiedFor(String permission) {
        // Exact permissions rather than role labels; forced password change denies.
        // The AuthPrincipal can only originate from the validated bearer filter.
        return authorization.requirePermission(permission);
    }

    private boolean assignedBranch(AuthPrincipal principal, UUID branchId) {
        List<Integer> matches = jdbc.queryForList("""
                SELECT 1
                  FROM public.org_membership_branch_scope s
                  JOIN public.branch b
                    ON b.tenant_id = s.tenant_id AND b.id = s.branch_id
                 WHERE s.tenant_id = ?
                   AND s.membership_id = ?
                   AND s.branch_id = ?
                """, Integer.class, principal.tenantId(), principal.membershipId(), branchId);
        return !matches.isEmpty();
    }

    /** Trusted internal-only context; not a transferable capability or HTTP DTO. */
    public record AuthorizedBranch(UUID tenantId, UUID membershipId, UUID branchId) {}

    /** Only IDs from an authorized branch; no patient demographics or contacts. */
    public record AuthorizedRegistration(UUID tenantId, UUID membershipId,
                                         UUID branchId, UUID registrationId, UUID patientId) {}
}
