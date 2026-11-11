package com.argdigital.aronexa.encounter;

import java.util.UUID;
import org.junit.jupiter.api.Test;

import static com.argdigital.aronexa.encounter.EncounterDraftContract.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** No patient information; pure internal contract acceptance. */
class EncounterDraftContractTests {
    private static Scope scope() {
        return new Scope(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID());
    }

    @Test void startsWithOnlyAVisitContextAndNoClinicalContent() {
        Draft draft = checkedIn(scope());
        assertThat(draft.stage()).isEqualTo(Stage.CHECKED_IN);
        assertThat(draft.consultationNote()).isEmpty();
        assertThat(draft.revision()).isZero();
        assertThat(draft.rxDraftRef()).isNull();
        assertThat(draft.billDraftRef()).isNull();
    }

    @Test void rejectsInvalidScope() {
        assertThatThrownBy(() -> new Scope(null, UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Scope(new UUID(0, 0), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void consultationDraftIsRevisionedAndImmutable() {
        Draft first = checkedIn(scope());
        Draft second = saveConsultation(first, 0, "Fictional consultation notes");
        Draft third = saveConsultation(second, 1, "Revised synthetic note");
        assertThat(first.stage()).isEqualTo(Stage.CHECKED_IN);
        assertThat(first.revision()).isZero();
        assertThat(second.stage()).isEqualTo(Stage.CONSULTATION_DRAFT);
        assertThat(second.revision()).isEqualTo(1);
        assertThat(second.consultationNote()).isEqualTo("Fictional consultation notes");
        assertThat(third.revision()).isEqualTo(2);
        assertThat(third.scope()).isSameAs(second.scope());
    }

    @Test void rejectsBlankOrOversizedClinicalNotes() {
        Draft first = checkedIn(scope());
        assertThatThrownBy(() -> saveConsultation(first, 0, " "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> saveConsultation(first, 0, "x".repeat(2001)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void staleRevisionNeverUpdates() {
        Draft draft = saveConsultation(checkedIn(scope()), 0, "Synthetic note");
        assertThatThrownBy(() -> saveConsultation(draft, 0, "Overwrite"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Stale");
    }

    @Test void cannotLinkPrescriptionBeforeConsultation() {
        assertThatThrownBy(() -> linkRxDraft(checkedIn(scope()), 0, UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test void cannotLinkBillingBeforePrescription() {
        Draft draft = saveConsultation(checkedIn(scope()), 0, "Synthetic note");
        assertThatThrownBy(() -> linkBillDraft(draft, 1, UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test void draftReferencesRequireExplicitValidIdentifiers() {
        Draft draft = saveConsultation(checkedIn(scope()), 0, "Synthetic note");
        assertThatThrownBy(() -> linkRxDraft(draft, 1, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> linkRxDraft(draft, 1, new UUID(0, 0)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void fullThreeStageDraftContractNeverFinalizes() {
        Draft consult = saveConsultation(checkedIn(scope()), 0, "Fictional consultation");
        UUID rx = UUID.randomUUID(), bill = UUID.randomUUID();
        Draft rxDraft = linkRxDraft(consult, 1, rx);
        Draft billDraft = linkBillDraft(rxDraft, 2, bill);
        assertThat(billDraft.revision()).isEqualTo(3);
        assertThat(billDraft.stage()).isEqualTo(Stage.BILL_DRAFT_LINKED);
        assertThat(billDraft.rxDraftRef()).isEqualTo(rx);
        assertThat(billDraft.billDraftRef()).isEqualTo(bill);
        assertThat(consult.rxDraftRef()).isNull();
        assertThat(rxDraft.billDraftRef()).isNull();
    }

    @Test void draftCannotBeForgedIntoInvalidStage() {
        Scope s = scope();
        assertThatThrownBy(() -> new Draft(s, Stage.RX_DRAFT_LINKED,
                1, "notes", null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Draft(s, Stage.CHECKED_IN,
                1, "", null, null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void defaultLogRepresentationRedactsNoteAndIdentity() {
        Draft draft = saveConsultation(checkedIn(scope()), 0,
                "DO-NOT-LOG-FICTIONAL-MEDICAL-CONTENT");
        assertThat(draft.toString()).doesNotContain("DO-NOT-LOG");
        assertThat(draft.scope().toString()).isEqualTo("EncounterScope[REDACTED]");
    }
}
