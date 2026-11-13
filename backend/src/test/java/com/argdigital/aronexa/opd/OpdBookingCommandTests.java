package com.argdigital.aronexa.opd;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.argdigital.aronexa.auth.AuthPrincipal;
import com.argdigital.aronexa.auth.TenantAuthorization;
import com.argdigital.aronexa.moduleregistry.ModuleAccessService;
import com.argdigital.aronexa.patient.PatientAccessService;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

class OpdBookingCommandTests {
    private UUID tenant,branch,patient,registration,doctor,staffId,session,appointment,requestId;
    private TenantAuthorization auth;
    private ModuleAccessService modules;
    private PatientAccessService patientAccess;
    private OpdBookingCommand.RestrictedVisitTransaction writer;
    private OpdBookingCommand subject;
    private AuthPrincipal actor;

    @BeforeEach
    void setup() {
        tenant=UUID.randomUUID();
        branch=UUID.randomUUID();
        patient=UUID.randomUUID();
        registration=UUID.randomUUID();
        doctor=UUID.randomUUID();
        staffId=UUID.randomUUID();
        session=UUID.randomUUID();
        appointment=UUID.randomUUID();
        requestId=UUID.randomUUID();
        auth=mock(TenantAuthorization.class);
        modules=mock(ModuleAccessService.class);
        patientAccess=mock(PatientAccessService.class);
        writer=mock(OpdBookingCommand.RestrictedVisitTransaction.class);
        actor=new AuthPrincipal(UUID.randomUUID(),tenant,staffId,"fictional_reception",
                false,List.of("RECEPTION"),List.of("APPOINTMENT_BOOK","APPOINTMENT_CHECK_IN"),
                session);
        when(auth.requirePermission("APPOINTMENT_BOOK")).thenReturn(actor);
        when(auth.requirePermission("APPOINTMENT_CHECK_IN")).thenReturn(actor);
        when(patientAccess.findReadableRegistration(branch,registration))
            .thenReturn(Optional.of(new PatientAccessService.AuthorizedRegistration(
                tenant,staffId,branch,registration,patient)));
        var handoff=new OpdRegistrationHandoffService(auth,patientAccess,modules);
        subject=new OpdBookingCommand(auth,modules,handoff,writer);
    }

    private OpdBookingCommand.BookingRequest booking() {
        return new OpdBookingCommand.BookingRequest(branch,registration,doctor,
                LocalDate.of(2026,11,14),LocalTime.of(10,0),requestId);
    }

    private OpdBookingCommand.BookingReceipt receipt(String stage,long revision) {
        return new OpdBookingCommand.BookingReceipt(tenant,branch,patient,
                registration,appointment,doctor,stage,revision,false);
    }

    @Test
    void bookPassesCurrentVerifiedSubjectToRestrictedTransactionNotBrowserPatient() {
        when(writer.bookWithinAuthorizedTransaction(eq(actor),any(),eq(booking())))
                .thenReturn(receipt("BOOKED",1));
        var result=subject.book(booking());
        assertThat(result.patientId()).isEqualTo(patient);
        verify(modules,atLeastOnce()).requireEnabled("OPD");
        verify(patientAccess).findReadableRegistration(branch,registration);
        var capture=org.mockito.ArgumentCaptor.forClass(
                OpdRegistrationHandoffService.VerifiedVisitSubject.class);
        verify(writer).bookWithinAuthorizedTransaction(eq(actor),capture.capture(),eq(booking()));
        assertThat(capture.getValue().patientId()).isEqualTo(patient);
        assertThat(capture.getValue().sessionId()).isEqualTo(session);
    }

    @Test
    void checkInRequiresFreshSessionAndExactAppointmentRevision() {
        var check=new OpdBookingCommand.CheckInRequest(branch,appointment,1,requestId);
        when(writer.checkInWithinAuthorizedTransaction(actor,check))
                .thenReturn(receipt("CHECKED_IN",2));
        assertThat(subject.checkIn(check).revision()).isEqualTo(2);
        verify(writer).checkInWithinAuthorizedTransaction(actor,check);
    }

    @Test
    void refusesOutOfBoundsSlotsAndRevisionOverflowBeforeAnyWrite() {
        for(LocalTime slot:List.of(LocalTime.of(8,45),LocalTime.of(16,59),
                LocalTime.of(10,1))) {
            var req=new OpdBookingCommand.BookingRequest(branch,registration,
                    doctor,LocalDate.of(2026,11,14),slot,requestId);
            assertThatThrownBy(()->subject.book(req))
                    .isInstanceOf(OpdBookingCommand.InvalidBooking.class);
        }
        assertThatThrownBy(()->subject.checkIn(new OpdBookingCommand.CheckInRequest(
                branch,appointment,Integer.MAX_VALUE,requestId)))
                .isInstanceOf(OpdBookingCommand.InvalidBooking.class);
        verifyNoInteractions(writer);
    }

    @Test
    void missingOrForgedBearerPrincipalCannotReachRestrictedWriter() {
        when(auth.requirePermission("APPOINTMENT_BOOK"))
                .thenThrow(new AccessDeniedException("No permission"));
        assertThatThrownBy(()->subject.book(booking()))
                .isInstanceOf(AccessDeniedException.class);
        when(auth.requirePermission("APPOINTMENT_CHECK_IN"))
                .thenReturn(new AuthPrincipal(actor.userId(),tenant,staffId,
                    actor.username(),false,actor.roles(),actor.permissions(),null));
        assertThatThrownBy(()->subject.checkIn(new OpdBookingCommand.CheckInRequest(
                branch,appointment,1,requestId)))
                .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(writer);
    }

    @Test
    void deniedLiveModuleOrRegistrationMappingBlocksBooking() {
        doThrow(new AccessDeniedException("Module disabled"))
                .when(modules).requireEnabled("OPD");
        assertThatThrownBy(()->subject.book(booking()))
                .isInstanceOf(AccessDeniedException.class);
        clearInvocations(writer);
        reset(modules);
        when(patientAccess.findReadableRegistration(branch,registration))
                .thenReturn(Optional.empty());
        assertThatThrownBy(()->subject.book(booking()))
                .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(writer);
    }

    @Test
    void rejectsForgedOrMissingDatabaseReceipts() {
        when(writer.bookWithinAuthorizedTransaction(eq(actor),any(),eq(booking())))
                .thenReturn(new OpdBookingCommand.BookingReceipt(UUID.randomUUID(),
                    branch,patient,registration,appointment,doctor,"BOOKED",1,false));
        assertThatThrownBy(()->subject.book(booking()))
                .isInstanceOf(AccessDeniedException.class);
        when(writer.bookWithinAuthorizedTransaction(eq(actor),any(),eq(booking())))
                .thenReturn(null);
        assertThatThrownBy(()->subject.book(booking()))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void staleOrCrossBranchCheckinReceiptRefused() {
        var check=new OpdBookingCommand.CheckInRequest(branch,appointment,1,requestId);
        when(writer.checkInWithinAuthorizedTransaction(actor,check))
                .thenReturn(new OpdBookingCommand.BookingReceipt(tenant,
                    UUID.randomUUID(),patient,registration,appointment,
                    doctor,"CHECKED_IN",2,false));
        assertThatThrownBy(()->subject.checkIn(check))
                .isInstanceOf(AccessDeniedException.class);
        when(writer.checkInWithinAuthorizedTransaction(actor,check))
                .thenReturn(receipt("CHECKED_IN",9));
        assertThatThrownBy(()->subject.checkIn(check))
                .isInstanceOf(AccessDeniedException.class);
    }
}
