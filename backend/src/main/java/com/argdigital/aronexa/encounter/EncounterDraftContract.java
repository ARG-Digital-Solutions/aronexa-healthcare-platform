package com.argdigital.aronexa.encounter;

import java.util.Objects;
import java.util.UUID;

/**
 * COMM-V1-T07: an internal, side-effect-free encounter draft contract.
 *
 * NOT an HTTP API, authentication decision, production persistence adapter,
 * clinical prescription, invoice, signature, or finalized medical record.
 *
 * Canonical IDs must eventually come from server-authorized P01 resources;
 * this contract never accepts a browser-supplied tenant or membership as trust.
 */
public final class EncounterDraftContract {
    private EncounterDraftContract() {}

    public enum Stage { CHECKED_IN, CONSULTATION_DRAFT, RX_DRAFT_LINKED, BILL_DRAFT_LINKED }

    /** Identity scope from verified internal services, not client-controlled DTOs. */
    public record Scope(UUID tenantId, UUID branchId, UUID patientId,
                        UUID encounterId, UUID doctorMembershipId) {
        public Scope {
            nonzero(tenantId, "tenantId");
            nonzero(branchId, "branchId");
            nonzero(patientId, "patientId");
            nonzero(encounterId, "encounterId");
            nonzero(doctorMembershipId, "doctorMembershipId");
        }

        @Override public String toString() { return "EncounterScope[REDACTED]"; }
    }

    /**
     * A non-finalized snapshot. The note may contain clinical information:
     * logging the record must not reveal it.
     */
    public record Draft(Scope scope, Stage stage, long revision,
                        String consultationNote, UUID rxDraftRef, UUID billDraftRef) {
        public Draft {
            Objects.requireNonNull(scope, "scope");
            Objects.requireNonNull(stage, "stage");
            Objects.requireNonNull(consultationNote, "consultationNote");
            if (revision < 0 || revision > 1_000_000_000L)
                throw new IllegalArgumentException("Invalid draft revision");
            if (consultationNote.length() > 2_000)
                throw new IllegalArgumentException("Draft note exceeds limit");
            boolean hasNote = !consultationNote.isBlank();
            switch (stage) {
                case CHECKED_IN -> {
                    if (revision != 0 || hasNote || rxDraftRef != null || billDraftRef != null)
                        throw new IllegalArgumentException("Invalid checked-in state");
                }
                case CONSULTATION_DRAFT -> {
                    if (revision == 0 || !hasNote || rxDraftRef != null || billDraftRef != null)
                        throw new IllegalArgumentException("Invalid consultation state");
                }
                case RX_DRAFT_LINKED -> {
                    if (revision == 0 || !hasNote || rxDraftRef == null || billDraftRef != null)
                        throw new IllegalArgumentException("Invalid Rx draft state");
                }
                case BILL_DRAFT_LINKED -> {
                    if (revision == 0 || !hasNote || rxDraftRef == null || billDraftRef == null)
                        throw new IllegalArgumentException("Invalid billing draft state");
                }
            }
        }

        @Override public String toString() {
            return "EncounterDraft[REDACTED,stage=" + stage + ",revision=" + revision + "]";
        }
    }

    private static void nonzero(UUID id, String field) {
        Objects.requireNonNull(id, field);
        if (id.equals(new UUID(0, 0)))
            throw new IllegalArgumentException("Invalid " + field);
    }

    private static void expect(Draft current, long revision, Stage expected) {
        Objects.requireNonNull(current, "current");
        if (current.revision() != revision)
            throw new IllegalStateException("Stale encounter draft revision");
        if (current.stage() != expected)
            throw new IllegalStateException("Encounter draft stage denied");
    }

    public static Draft checkedIn(Scope scope) {
        return new Draft(scope, Stage.CHECKED_IN, 0, "", null, null);
    }

    public static Draft saveConsultation(Draft current, long revision, String note) {
        Objects.requireNonNull(note, "note");
        Objects.requireNonNull(current, "current");
        if (current.revision() != revision)
            throw new IllegalStateException("Stale encounter draft revision");
        if (current.stage() != Stage.CHECKED_IN
                && current.stage() != Stage.CONSULTATION_DRAFT)
            throw new IllegalStateException("Encounter draft stage denied");
        if (note.isBlank() || note.length() > 2_000)
            throw new IllegalArgumentException("Invalid consultation note");
        return new Draft(current.scope(), Stage.CONSULTATION_DRAFT,
                revision + 1, note.strip(), null, null);
    }

    public static Draft linkRxDraft(Draft current, long revision, UUID rxDraftRef) {
        expect(current, revision, Stage.CONSULTATION_DRAFT);
        nonzero(rxDraftRef, "rxDraftRef");
        return new Draft(current.scope(), Stage.RX_DRAFT_LINKED,
                revision + 1, current.consultationNote(), rxDraftRef, null);
    }

    public static Draft linkBillDraft(Draft current, long revision, UUID billDraftRef) {
        expect(current, revision, Stage.RX_DRAFT_LINKED);
        nonzero(billDraftRef, "billDraftRef");
        return new Draft(current.scope(), Stage.BILL_DRAFT_LINKED,
                revision + 1, current.consultationNote(), current.rxDraftRef(), billDraftRef);
    }
}
