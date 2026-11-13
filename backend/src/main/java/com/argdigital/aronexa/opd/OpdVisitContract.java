package com.argdigital.aronexa.opd;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * R1-10A production integration contract, NOT a Spring bean or endpoint.
 *
 * A trusted backend adapter must derive effective tenant and actor membership
 * from current authentication; an arbitrary body/header/URL UUID cannot grant
 * authorization. Real persistence/RLS remains unimplemented here.
 */
public final class OpdVisitContract {
    private OpdVisitContract() { }

    public enum Stage {
        REGISTERED, BOOKED, CHECKED_IN, CONSULTATION_DRAFT,
        PRESCRIPTION_DRAFT, BILLED
    }

    public enum Action {
        REGISTER, BOOK, CHECK_IN, SAVE_CONSULTATION,
        SAVE_PRESCRIPTION_DRAFT, CREATE_BILL
    }

    public record Scope(
            UUID tenantId, UUID branchId, UUID patientId,
            UUID registrationId, UUID appointmentId,
            UUID doctorMembershipId) {
        public Scope {
            required(tenantId, "tenant");
            required(branchId, "branch");
            required(patientId, "patient");
            required(registrationId, "registration");
            // Appointment and doctor are absent until a booking exists.
            if ((appointmentId == null) != (doctorMembershipId == null)) {
                throw new IllegalArgumentException("Partial visit linkage");
            }
            if (appointmentId != null) {
                required(appointmentId, "appointment");
                required(doctorMembershipId, "doctor membership");
            }
        }
    }

    public record Visit(Scope scope, Stage stage, long revision,
                        LocalDate visitDate) {
        public Visit {
            Objects.requireNonNull(scope, "scope");
            Objects.requireNonNull(stage, "stage");
            if (revision < 1 || revision == Long.MAX_VALUE) {
                throw new IllegalArgumentException("Invalid revision");
            }
            if ((stage == Stage.REGISTERED) !=
                    (scope.appointmentId() == null)) {
                throw new IllegalArgumentException("Stage/linkage mismatch");
            }
            if (stage != Stage.REGISTERED && visitDate == null) {
                throw new IllegalArgumentException("Missing visit date");
            }
        }
    }

    /** Pure optimistic state transition; persistence MUST lock, authorize
     * and audit in the SAME restricted transaction before applying it. */
    public static Visit transition(Visit current, Action action,
                                   long expectedRevision) {
        Objects.requireNonNull(current, "visit");
        Objects.requireNonNull(action, "action");
        if (current.revision() != expectedRevision) {
            throw new Conflict("Stale OPD visit revision");
        }
        Stage target = switch (action) {
            case CHECK_IN -> require(current.stage(), Stage.BOOKED,
                    Stage.CHECKED_IN);
            case SAVE_CONSULTATION -> require(current.stage(),
                    Stage.CHECKED_IN, Stage.CONSULTATION_DRAFT);
            case SAVE_PRESCRIPTION_DRAFT -> require(current.stage(),
                    Stage.CONSULTATION_DRAFT, Stage.PRESCRIPTION_DRAFT);
            case CREATE_BILL -> {
                if (current.stage() != Stage.CONSULTATION_DRAFT
                        && current.stage() != Stage.PRESCRIPTION_DRAFT) {
                    throw new Conflict("Consultation required for billing");
                }
                yield Stage.BILLED;
            }
            // Registration and booking require authoritative new scope, and
            // cannot be applied through a generic mutation of an old visit.
            case REGISTER, BOOK -> throw new Conflict(
                    "Registration/booking require a dedicated trusted command");
        };
        return new Visit(current.scope(), target, current.revision() + 1,
                current.visitDate());
    }

    private static Stage require(Stage actual, Stage expected, Stage next) {
        if (actual != expected) {
            throw new Conflict("Invalid OPD visit transition");
        }
        return next;
    }

    public static void verifyStableScope(Visit original, Visit updated) {
        Objects.requireNonNull(original, "original");
        Objects.requireNonNull(updated, "updated");
        if (!original.scope().equals(updated.scope())
                || !Objects.equals(original.visitDate(), updated.visitDate())
                || updated.revision() != original.revision() + 1) {
            throw new Conflict("Cross-visit mutation refused");
        }
    }

    private static void required(UUID id, String field) {
        if (id == null || id.equals(new UUID(0, 0))) {
            throw new IllegalArgumentException("Invalid " + field);
        }
    }

    public static final class Conflict extends RuntimeException {
        public Conflict(String message) {
            super(message);
        }
    }
}
