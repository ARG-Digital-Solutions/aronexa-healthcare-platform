package com.argdigital.aronexa.encounter;

import com.argdigital.aronexa.auth.AuthPrincipal;
import com.argdigital.aronexa.auth.TenantAuthorization;
import java.util.Objects;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.springframework.security.access.AccessDeniedException;

import static com.argdigital.aronexa.encounter.EncounterDraftContract.*;

/**
 * COMM-V1-T08 — internal, unregistered encounter draft command boundary.
 *
 * No HTTP route, Spring component, production datasource, migration or clinical
 * finalization is introduced. The port MUST resolve and re-check module state,
 * session/membership, doctor assignment and branch ownership INSIDE the same
 * restricted-role SQL transaction used for the read/write and metadata audit.
 * A caller-selected tenant/branch/doctor or an unchecked SQL GUC is NEVER proof.
 */
public final class EncounterDraftCommandCoordinator {
    public static final String DRAFT_PERMISSION = "OPD_DRAFT_WRITE";

    /**
     * Trusted internal adapter contract, NOT suitable for browser/user input.
     * Implementations must enforce live access with verified actor details,
     * transaction-bound RLS, optimistic update and atomic audit persistence.
     */
    @FunctionalInterface
    public interface AuthorizedDraftTransaction {
        Draft change(AuthPrincipal actor, UUID encounterId, UnaryOperator<Draft> command);
    }

    private final TenantAuthorization authorization;
    private final AuthorizedDraftTransaction persistence;

    public EncounterDraftCommandCoordinator(TenantAuthorization authorization,
                                            AuthorizedDraftTransaction persistence) {
        this.authorization = Objects.requireNonNull(authorization, "authorization");
        this.persistence = Objects.requireNonNull(persistence, "persistence");
    }

    public Draft saveConsultation(UUID encounterId, long revision, String note) {
        return execute(encounterId, draft -> saveConsultation(draft, revision, note));
    }

    public Draft linkRxDraft(UUID encounterId, long revision, UUID rxDraftRef) {
        return execute(encounterId, draft -> linkRxDraft(draft, revision, rxDraftRef));
    }

    public Draft linkBillDraft(UUID encounterId, long revision, UUID billDraftRef) {
        return execute(encounterId, draft -> linkBillDraft(draft, revision, billDraftRef));
    }

    private Draft execute(UUID encounterId, UnaryOperator<Draft> command) {
        // Resolve authenticated membership from Spring Security, never URL/body.
        AuthPrincipal principal = authorization.requirePermission(DRAFT_PERMISSION);
        if (encounterId == null || encounterId.equals(new UUID(0, 0))) {
            throw new AccessDeniedException("Encounter access denied");
        }
        return persistence.change(principal, encounterId, current -> {
            if (current == null || current.scope() == null
                    || !encounterId.equals(current.scope().encounterId())
                    || !principal.tenantId().equals(current.scope().tenantId())
                    || !principal.membershipId().equals(current.scope().doctorMembershipId())) {
                throw new AccessDeniedException("Encounter access denied");
            }
            // Additional current branch/module/session checks MUST be performed
            // by the transactional store against live DB facts before this call.
            Draft updated = command.apply(current);
            if (updated == null || !updated.scope().equals(current.scope())
                    || updated.revision() != current.revision() + 1
                    || updated.stage() == Stage.CHECKED_IN) {
                throw new IllegalStateException("Invalid draft transition");
            }
            return updated;
        });
    }

    // Explicit qualification avoids accidental recursion with these public
    // coordinator methods: contract contains the pure transition rules.
    private static Draft saveConsultation(Draft d, long rev, String text) {
        return EncounterDraftContract.saveConsultation(d, rev, text);
    }
    private static Draft linkRxDraft(Draft d, long rev, UUID id) {
        return EncounterDraftContract.linkRxDraft(d, rev, id);
    }
    private static Draft linkBillDraft(Draft d, long rev, UUID id) {
        return EncounterDraftContract.linkBillDraft(d, rev, id);
    }
}
