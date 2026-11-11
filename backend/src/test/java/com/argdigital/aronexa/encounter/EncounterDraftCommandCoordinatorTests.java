package com.argdigital.aronexa.encounter;

import com.argdigital.aronexa.auth.AuthPrincipal;
import com.argdigital.aronexa.auth.TenantAuthorization;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import static com.argdigital.aronexa.encounter.EncounterDraftContract.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Focused synthetic guard tests; not a replacement for live SQL permission tests. */
class EncounterDraftCommandCoordinatorTests {
    private final Scope scope = new Scope(UUID.randomUUID(), UUID.randomUUID(),
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    private final AtomicReference<Draft> snapshot = new AtomicReference<>(checkedIn(scope));
    private final EncounterDraftCommandCoordinator.AuthorizedDraftTransaction port =
            (principal, encounterId, update) -> {
                Draft original = snapshot.get();
                Draft changed = update.apply(original);
                if (!snapshot.compareAndSet(original, changed))
                    throw new IllegalStateException("Concurrent change");
                return changed;
            };
    private final EncounterDraftCommandCoordinator coordinator =
            new EncounterDraftCommandCoordinator(new TenantAuthorization(), port);

    private void login(UUID tenant, UUID doctor, boolean changePassword, List<String> permissions) {
        AuthPrincipal principal = new AuthPrincipal(UUID.randomUUID(), tenant, doctor,
                "fictional", changePassword, List.of("DOCTOR"), permissions);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }
    private void doctor() {
        login(scope.tenantId(), scope.doctorMembershipId(), false,
                List.of(EncounterDraftCommandCoordinator.DRAFT_PERMISSION));
    }
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    @Test void authenticatedDoctorCanCreateRevisionedDraft() {
        doctor();
        Draft draft = coordinator.saveConsultation(scope.encounterId(), 0, "Fictional notes");
        assertThat(draft.stage()).isEqualTo(Stage.CONSULTATION_DRAFT);
        assertThat(draft.revision()).isEqualTo(1);
        assertThat(snapshot.get()).isEqualTo(draft);
    }
    @Test void noSessionFailsBeforePersistence() {
        assertThatThrownBy(() -> coordinator.saveConsultation(scope.encounterId(), 0, "notes"))
                .isInstanceOf(org.springframework.security.core.AuthenticationException.class);
        assertThat(snapshot.get().revision()).isZero();
    }
    @Test void cannotWriteWithoutExplicitPermission() {
        login(scope.tenantId(), scope.doctorMembershipId(), false, List.of("ORG_READ"));
        assertThatThrownBy(() -> coordinator.saveConsultation(scope.encounterId(), 0, "notes"))
                .isInstanceOf(AccessDeniedException.class);
    }
    @Test void passwordChangeLockRefusesClinicalWrite() {
        login(scope.tenantId(), scope.doctorMembershipId(), true,
                List.of(EncounterDraftCommandCoordinator.DRAFT_PERMISSION));
        assertThatThrownBy(() -> coordinator.saveConsultation(scope.encounterId(), 0, "notes"))
                .isInstanceOf(AccessDeniedException.class);
    }
    @Test void tenantMismatchRefusesBeforeAnyMutation() {
        login(UUID.randomUUID(), scope.doctorMembershipId(), false,
                List.of(EncounterDraftCommandCoordinator.DRAFT_PERMISSION));
        assertThatThrownBy(() -> coordinator.saveConsultation(scope.encounterId(), 0, "notes"))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(snapshot.get().revision()).isZero();
    }
    @Test void wrongAssignedDoctorMembershipRefuses() {
        login(scope.tenantId(), UUID.randomUUID(), false,
                List.of(EncounterDraftCommandCoordinator.DRAFT_PERMISSION));
        assertThatThrownBy(() -> coordinator.saveConsultation(scope.encounterId(), 0, "notes"))
                .isInstanceOf(AccessDeniedException.class);
    }
    @Test void differentEncounterIdCannotChangeRow() {
        doctor();
        assertThatThrownBy(() -> coordinator.saveConsultation(UUID.randomUUID(), 0, "notes"))
                .isInstanceOf(AccessDeniedException.class);
    }
    @Test void emptyEncounterIdFailsClosed() {
        doctor();
        assertThatThrownBy(() -> coordinator.saveConsultation(new UUID(0, 0), 0, "notes"))
                .isInstanceOf(AccessDeniedException.class);
    }
    @Test void staleRevisionRefusesWithoutNewState() {
        doctor();
        coordinator.saveConsultation(scope.encounterId(), 0, "First fictional note");
        assertThatThrownBy(() -> coordinator.saveConsultation(scope.encounterId(), 0, "Overwrite"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Stale");
        assertThat(snapshot.get().consultationNote()).isEqualTo("First fictional note");
    }
    @Test void cannotLinkRxWithoutConsultation() {
        doctor();
        assertThatThrownBy(() -> coordinator.linkRxDraft(scope.encounterId(), 0, UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class);
    }
    @Test void cannotLinkBillWithoutRxDraft() {
        doctor();
        Draft consult = coordinator.saveConsultation(scope.encounterId(), 0, "Synthetic");
        assertThatThrownBy(() -> coordinator.linkBillDraft(scope.encounterId(), consult.revision(), UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class);
    }
    @Test void orderedDraftReferencesNeverFinalizeMedicalOrFinancialRecords() {
        doctor();
        Draft first = coordinator.saveConsultation(scope.encounterId(), 0, "Synthetic");
        UUID rx = UUID.randomUUID(), bill = UUID.randomUUID();
        Draft second = coordinator.linkRxDraft(scope.encounterId(), first.revision(), rx);
        Draft third = coordinator.linkBillDraft(scope.encounterId(), second.revision(), bill);
        assertThat(third.stage()).isEqualTo(Stage.BILL_DRAFT_LINKED);
        assertThat(third.revision()).isEqualTo(3);
        assertThat(third.scope()).isEqualTo(scope);
        assertThat(third.rxDraftRef()).isEqualTo(rx);
        assertThat(third.billDraftRef()).isEqualTo(bill);
        assertThat(first.rxDraftRef()).isNull();
    }
    @Test void roleNameCannotBypassPermissionGrant() {
        login(scope.tenantId(), scope.doctorMembershipId(), false, List.of());
        assertThatThrownBy(() -> coordinator.saveConsultation(scope.encounterId(), 0, "notes"))
                .isInstanceOf(AccessDeniedException.class);
    }
    @Test void returnedDraftLogDoesNotExposeClinicalText() {
        doctor();
        Draft draft = coordinator.saveConsultation(scope.encounterId(), 0, "SECRET-FICTIONAL-NOTE");
        assertThat(draft.toString()).doesNotContain("SECRET-FICTIONAL-NOTE");
    }
}
