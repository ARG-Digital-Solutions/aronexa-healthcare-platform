package com.argdigital.aronexa.opd;

import static org.assertj.core.api.Assertions.*;
import static com.argdigital.aronexa.opd.OpdVisitContract.*;

import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** R1-10A pure domain tests; no database, HTTP or clinical activation. */
class OpdVisitContractTests {
    private static UUID id() { return UUID.randomUUID(); }

    private static Scope assignedScope() {
        return new Scope(id(), id(), id(), id(), id(), id());
    }

    private static Visit visit(Stage stage) {
        return new Visit(assignedScope(), stage, 2, LocalDate.of(2026, 11, 13));
    }

    @Test
    void checkedInToConsultationToRxToBillUsesDistinctOptimisticRevisions() {
        Visit booked=visit(Stage.BOOKED);
        Visit checked=transition(booked, Action.CHECK_IN, 2);
        assertThat(checked.stage()).isEqualTo(Stage.CHECKED_IN);
        Visit consulted=transition(checked, Action.SAVE_CONSULTATION, 3);
        assertThat(consulted.stage()).isEqualTo(Stage.CONSULTATION_DRAFT);
        Visit rx=transition(consulted, Action.SAVE_PRESCRIPTION_DRAFT, 4);
        assertThat(rx.stage()).isEqualTo(Stage.PRESCRIPTION_DRAFT);
        Visit billed=transition(rx, Action.CREATE_BILL, 5);
        assertThat(billed.stage()).isEqualTo(Stage.BILLED);
        assertThat(billed.revision()).isEqualTo(6);
        verifyStableScope(booked, checked);
        verifyStableScope(checked, consulted);
        verifyStableScope(consulted, rx);
        verifyStableScope(rx, billed);
    }

    @Test
    void consultationAllowsDirectBillWithoutRxFinalization() {
        Visit consultation=visit(Stage.CONSULTATION_DRAFT);
        Visit bill=transition(consultation, Action.CREATE_BILL, 2);
        assertThat(bill.stage()).isEqualTo(Stage.BILLED);
    }

    @Test
    void draftNeverFinalizesOrIssuesPrescription() {
        assertThat(Stage.values()).doesNotContainNull();
        assertThat(Stage.values()).containsExactly(
                Stage.REGISTERED, Stage.BOOKED, Stage.CHECKED_IN,
                Stage.CONSULTATION_DRAFT, Stage.PRESCRIPTION_DRAFT, Stage.BILLED);
        assertThatThrownBy(() -> transition(visit(Stage.PRESCRIPTION_DRAFT),
                Action.SAVE_CONSULTATION, 2)).isInstanceOf(Conflict.class);
        assertThatThrownBy(() -> transition(visit(Stage.BILLED),
                Action.SAVE_PRESCRIPTION_DRAFT, 2)).isInstanceOf(Conflict.class);
    }

    @Test
    void staleOrOutOfOrderActionsAlwaysFailClosed() {
        Visit booked=visit(Stage.BOOKED);
        assertThatThrownBy(() -> transition(booked, Action.CHECK_IN, 1))
                .isInstanceOf(Conflict.class);
        assertThatThrownBy(() -> transition(booked,
                Action.SAVE_CONSULTATION, 2)).isInstanceOf(Conflict.class);
        assertThatThrownBy(() -> transition(booked,
                Action.CREATE_BILL, 2)).isInstanceOf(Conflict.class);
        assertThatThrownBy(() -> transition(booked,
                Action.REGISTER, 2)).isInstanceOf(Conflict.class);
        assertThatThrownBy(() -> transition(booked,
                Action.BOOK, 2)).isInstanceOf(Conflict.class);
        assertThatThrownBy(() -> transition(
                visit(Stage.CHECKED_IN), Action.SAVE_PRESCRIPTION_DRAFT, 2))
                .isInstanceOf(Conflict.class);
    }

    @Test
    void visitScopeRequiresCompleteCanonicalObjects() {
        assertThatThrownBy(() -> new Scope(
                id(),id(),id(),id(),id(),null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Scope(
                id(),id(),id(),id(),null,id()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Scope(
                new UUID(0,0),id(),id(),id(),null,null))
                .isInstanceOf(IllegalArgumentException.class);
        Scope unbooked=new Scope(id(),id(),id(),id(),null,null);
        assertThat(new Visit(unbooked,Stage.REGISTERED,1,null).stage())
                .isEqualTo(Stage.REGISTERED);
        assertThatThrownBy(() -> new Visit(unbooked,Stage.BOOKED,1,null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void mutationCannotSwapPatientTenantBranchDoctorOrDate() {
        Visit original=visit(Stage.CHECKED_IN);
        Visit good=transition(original,Action.SAVE_CONSULTATION,2);
        verifyStableScope(original,good);
        Scope s=good.scope();
        Scope forged=new Scope(id(),s.branchId(),s.patientId(),
                s.registrationId(),s.appointmentId(),s.doctorMembershipId());
        Visit changed=new Visit(forged,Stage.CONSULTATION_DRAFT,3,good.visitDate());
        assertThatThrownBy(() -> verifyStableScope(original,changed))
                .isInstanceOf(Conflict.class);
        Visit wrongDate=new Visit(s,Stage.CONSULTATION_DRAFT,3,good.visitDate().plusDays(1));
        assertThatThrownBy(() -> verifyStableScope(original,wrongDate))
                .isInstanceOf(Conflict.class);
    }
}
