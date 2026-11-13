package com.argdigital.aronexa.opd;

import com.argdigital.aronexa.auth.AuthPrincipal;
import com.argdigital.aronexa.auth.TenantAuthorization;
import com.argdigital.aronexa.patient.PatientAccessService;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

/**
 * R1-10A first source-owned OPD integration boundary: resolve an exact
 * registration -> patient mapping through the existing dedicated patient
 * PostgreSQL runtime connection, with current branch/module/RLS checks.
 *
 * IMPORTANT: This is NOT a booking authorization token or a clinical HTTP
 * endpoint. Never trust a patient ID, tenant ID or membership supplied by an
 * HTTP client. A future appointment write must repeat the live session,
 * permission, branch, module and object checks inside its OWN restricted SQL
 * transaction, then enforce composite foreign keys and slot uniqueness.
 *
 * This class performs NO appointment write, migration, fee update or booking.
 * It deliberately cannot activate an unreleased module or grant roles.
 */
@Service
public final class OpdRegistrationHandoffService {
    private static final UUID NIL = new UUID(0, 0);
    private static final String BOOK_PERMISSION = "APPOINTMENT_BOOK";
    private final TenantAuthorization authorization;
    private final PatientAccessService registrations;

    public OpdRegistrationHandoffService(TenantAuthorization authorization,
                                         PatientAccessService registrations) {
        this.authorization = authorization;
        this.registrations = registrations;
    }

    /**
     * Live, read-only registration resolution; no demographics, contacts,
     * clinical notes, prescription, billing fields, or client-selected patient.
     * PatientAccessService audits successful reads inside its patient SQL
     * transaction and does not reuse a pooled tenant/member session variable.
     *
     * This returned record must NOT be treated as durable permission for any
     * later write, background worker, retry, or different HTTP session.
     */
    public VerifiedVisitSubject resolveForBooking(UUID branchId, UUID registrationId) {
        AuthPrincipal staff = authorization.requirePermission(BOOK_PERMISSION);
        if (staff.sessionId() == null || invalid(branchId) || invalid(registrationId)) {
            throw denied();
        }
        // This second permission check is independently enforced by
        // PatientAccessService, which also checks the live registration module,
        // membership/branch SQL scope and PostgreSQL patient runtime RLS.
        var registration = registrations.findReadableRegistration(branchId, registrationId)
                .orElseThrow(OpdRegistrationHandoffService::denied);

        if (!staff.tenantId().equals(registration.tenantId())
                || !staff.membershipId().equals(registration.membershipId())
                || !branchId.equals(registration.branchId())
                || !registrationId.equals(registration.registrationId())
                || invalid(registration.patientId())) {
            throw denied();
        }

        return new VerifiedVisitSubject(registration.tenantId(),
                registration.branchId(), registration.registrationId(),
                registration.patientId(), staff.membershipId(), staff.sessionId());
    }

    private static boolean invalid(UUID id) {
        return id == null || NIL.equals(id);
    }

    private static AccessDeniedException denied() {
        return new AccessDeniedException("OPD registration handoff denied");
    }

    /**
     * Internal request-local read result only, NOT an authorization capability.
     * Do not log or expose the membership/session UUIDs in an API response.
     */
    public record VerifiedVisitSubject(UUID tenantId, UUID branchId,
                                       UUID registrationId, UUID patientId,
                                       UUID actorMembershipId, UUID sessionId) {}
}
