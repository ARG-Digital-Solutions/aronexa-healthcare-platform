package com.argdigital.aronexa.patient;

import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only, session-authorized registration reference.
 *
 * Does NOT open patient registration for writing, select demographics, expose
 * identifiers/contacts, or bypass the unreleased PATIENT_REGISTRATION module.
 * PatientAccessService enforces the permission, tenant, branch membership,
 * live module entitlement, scoped patient-role RLS and an append-only read audit.
 *
 * A missing or invisible registration always returns the same no-store 404.
 */
@RestController
@RequestMapping("/api/v1/branches/{branchId}/patient-registrations")
public class PatientRegistrationReadController {
    private final PatientAccessService access;

    public PatientRegistrationReadController(PatientAccessService access) {
        this.access = access;
    }

    /** Omit membership ID and all demographics, MRNs and clinical content. */
    public record RegistrationReference(UUID tenantId, UUID branchId,
                                        UUID registrationId, UUID patientId) {}

    @GetMapping("/{registrationId}")
    public ResponseEntity<RegistrationReference> find(
            @PathVariable UUID branchId, @PathVariable UUID registrationId) {
        return access.findReadableRegistration(branchId, registrationId)
                .map(found -> ResponseEntity.ok()
                        .cacheControl(CacheControl.noStore())
                        .body(new RegistrationReference(
                                found.tenantId(), found.branchId(),
                                found.registrationId(), found.patientId())))
                .orElseGet(() -> ResponseEntity.notFound()
                        .cacheControl(CacheControl.noStore()).build());
    }
}
