package com.argdigital.aronexa.opd;

import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * PROD-INT-01: the first production-source authenticated OPD intake read.
 *
 * It resolves a pre-existing, authorized registration into an immutable
 * reference for the receptionist. It never creates an appointment, reads
 * demographics, issues a booking capability, or accepts a client-selected
 * patient/tenant. The shared service independently checks live session,
 * permissions, OPD module entitlement, branch scope and patient SQL RLS.
 *
 * The production OPD module remains unreleased by default. Activation,
 * appointment writes and real-clinic use require separate review/approval.
 */
@RestController
@RequestMapping("/api/v1/branches/{branchId}/opd/intake")
public final class OpdIntakeController {
    private final OpdRegistrationHandoffService intake;

    public OpdIntakeController(OpdRegistrationHandoffService intake) {
        this.intake = intake;
    }

    /** Pseudonymous IDs only. No membership, session, clinical or PII fields. */
    public record IntakeReference(
            UUID tenantId, UUID branchId, UUID registrationId, UUID patientId,
            String status) {}

    @GetMapping("/{registrationId}")
    public ResponseEntity<IntakeReference> find(
            @PathVariable UUID branchId, @PathVariable UUID registrationId) {
        if (branchId == null || registrationId == null
                || branchId.equals(new UUID(0, 0))
                || registrationId.equals(new UUID(0, 0))) {
            throw new AccessDeniedException("OPD intake denied");
        }
        var trusted = intake.resolveForBooking(branchId, registrationId);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new IntakeReference(
                        trusted.tenantId(), trusted.branchId(),
                        trusted.registrationId(), trusted.patientId(),
                        "REGISTRATION_VERIFIED"));
    }
}
