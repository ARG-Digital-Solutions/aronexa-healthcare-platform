package com.argdigital.aronexa.opd;

import com.argdigital.aronexa.auth.AuthPrincipal;
import com.argdigital.aronexa.auth.TenantAuthorization;
import com.argdigital.aronexa.moduleregistry.ModuleAccessService;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Objects;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;

/**
 * R1-10B2 protected, production-SOURCE booking/check-in command boundary.
 *
 * Deliberately NOT a Spring bean or HTTP endpoint. Actual appointment SQL
 * writer remains unavailable until a distinct, independently reviewed,
 * least-privileged connection and transactional authorization are proven.
 * Never replace the port with a privileged test migrator in a clinic runtime.
 */
public final class OpdBookingCommand {
    private static final UUID NIL = new UUID(0L,0L);
    public static final String BOOK = "APPOINTMENT_BOOK";
    public static final String CHECK_IN = "APPOINTMENT_CHECK_IN";

    private final TenantAuthorization auth;
    private final ModuleAccessService modules;
    private final OpdRegistrationHandoffService handoff;
    private final RestrictedVisitTransaction writes;

    public OpdBookingCommand(TenantAuthorization auth,
            ModuleAccessService modules,
            OpdRegistrationHandoffService handoff,
            RestrictedVisitTransaction writes) {
        this.auth = Objects.requireNonNull(auth, "auth");
        this.modules = Objects.requireNonNull(modules, "modules");
        this.handoff = Objects.requireNonNull(handoff, "handoff");
        this.writes = Objects.requireNonNull(writes, "writes");
    }

    /**
     * Untrusted browser inputs have NO patient or tenant choice. A fresh
     * server-authorized registration->patient mapping is resolved per call.
     */
    public BookingReceipt book(BookingRequest request) {
        Objects.requireNonNull(request, "request");
        validate(request.branchId());
        validate(request.registrationId());
        validate(request.doctorMembershipId());
        validate(request.requestId());
        if (!validSlot(request.slotStart()) || request.visitDate()==null
                || request.visitDate().isBefore(LocalDate.of(2000,1,1))
                || request.visitDate().isAfter(LocalDate.of(2100,12,31))) {
            throw new InvalidBooking();
        }
        AuthPrincipal actor = authenticated(BOOK);
        modules.requireEnabled("OPD");
        var exact = handoff.resolveForBooking(request.branchId(),
                request.registrationId());
        if (!exact.tenantId().equals(actor.tenantId())
                || !exact.actorMembershipId().equals(actor.membershipId())
                || !exact.sessionId().equals(actor.sessionId())
                || !exact.branchId().equals(request.branchId())
                || !exact.registrationId().equals(request.registrationId())
                || exact.patientId()==null || NIL.equals(exact.patientId())) {
            throw denied();
        }
        // The port MUST atomically revalidate live token/session, roles,
        // branch, doctor, entitlement, object and immutable audit in SQL.
        // This read-only handoff cannot grant a future write.
        var result = writes.bookWithinAuthorizedTransaction(actor,exact,request);
        verifyReceipt(actor, exact, request, result);
        return result;
    }

    public BookingReceipt checkIn(CheckInRequest request) {
        Objects.requireNonNull(request, "request");
        validate(request.branchId());
        validate(request.appointmentId());
        validate(request.requestId());
        if (request.expectedRevision() < 1 || request.expectedRevision()==Integer.MAX_VALUE) throw new InvalidBooking();
        AuthPrincipal actor = authenticated(CHECK_IN);
        modules.requireEnabled("OPD");
        // A check-in cannot reuse an earlier handoff: the transactional
        // writer MUST read exact appointment registration/patient/branch
        // and live staff authorization inside the same DB transaction.
        var result = writes.checkInWithinAuthorizedTransaction(actor,request);
        if (result==null || !Objects.equals(actor.tenantId(),result.tenantId())
                || !Objects.equals(request.branchId(),result.branchId())
                || !Objects.equals(request.appointmentId(),result.appointmentId())
                || !"CHECKED_IN".equals(result.stage())
                || result.revision() != request.expectedRevision()+1) {
            throw denied();
        }
        return result;
    }

    private AuthPrincipal authenticated(String permission) {
        AuthPrincipal actor=auth.requirePermission(permission);
        if (actor.sessionId()==null || NIL.equals(actor.sessionId())
                || actor.membershipId()==null || NIL.equals(actor.membershipId())
                || actor.tenantId()==null || NIL.equals(actor.tenantId())) {
            throw denied();
        }
        return actor;
    }

    private static void verifyReceipt(AuthPrincipal actor,
            OpdRegistrationHandoffService.VerifiedVisitSubject exact,
            BookingRequest request,BookingReceipt result) {
        if(result==null || !Objects.equals(result.tenantId(),actor.tenantId())
                || !Objects.equals(result.branchId(),exact.branchId())
                || !Objects.equals(result.registrationId(),exact.registrationId())
                || !Objects.equals(result.patientId(),exact.patientId())
                || !Objects.equals(result.doctorMembershipId(),
                                   request.doctorMembershipId())
                || result.appointmentId()==null || NIL.equals(result.appointmentId())
                || !(("BOOKED".equals(result.stage())) ||
                     (result.replayed() &&
                       ("CHECKED_IN".equals(result.stage()) ||
                        "CANCELLED".equals(result.stage()))))
                || result.revision()<1) {
            throw denied();
        }
    }

    private static void validate(UUID id) {
        if(id==null || NIL.equals(id)) throw new InvalidBooking();
    }

    private static boolean validSlot(LocalTime time) {
        return time!=null && time.getSecond()==0 && time.getNano()==0
                && time.getMinute()%15==0
                && !time.isBefore(LocalTime.of(9,0))
                && !time.isAfter(LocalTime.of(16,45));
    }

    private static AccessDeniedException denied() {
        return new AccessDeniedException("OPD booking denied");
    }

    public record BookingRequest(UUID branchId,UUID registrationId,
            UUID doctorMembershipId,LocalDate visitDate,LocalTime slotStart,
            UUID requestId) {}
    public record CheckInRequest(UUID branchId,UUID appointmentId,
            int expectedRevision,UUID requestId) {}
    public record BookingReceipt(UUID tenantId,UUID branchId,UUID patientId,
            UUID registrationId,UUID appointmentId,UUID doctorMembershipId,
            String stage,long revision,boolean replayed) {}

    /**
     * Security critical: implementations MUST use a dedicated nonowner SQL
     * login and DB-verified current auth session in the same transaction as
     * DML and event audit. No implementation is yet registered in production.
     */
    public interface RestrictedVisitTransaction {
        BookingReceipt bookWithinAuthorizedTransaction(AuthPrincipal actor,
                OpdRegistrationHandoffService.VerifiedVisitSubject subject,
                BookingRequest request);
        BookingReceipt checkInWithinAuthorizedTransaction(AuthPrincipal actor,
                CheckInRequest request);
    }

    public static final class InvalidBooking extends RuntimeException {
        public InvalidBooking() { super("Invalid OPD booking request"); }
    }
}
