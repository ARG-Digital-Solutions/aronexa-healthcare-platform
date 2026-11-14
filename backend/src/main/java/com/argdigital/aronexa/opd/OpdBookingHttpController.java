package com.argdigital.aronexa.opd;

import com.argdigital.aronexa.auth.TenantAuthorization;
import com.argdigital.aronexa.moduleregistry.ModuleAccessService;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.*;

/**
 * PROD-INT-02 protected production-source HTTP command boundary.
 *
 * DEFAULT INACTIVE. Registered only with explicit reviewed-activation=true.
 * Even then, without a separately supplied restricted writer bean, both
 * commands return 503. No test controller, migrator, SQL password, key or
 * auto-provisioned module is used by this class.
 *
 * A future customer activation MUST first independently review and install
 * the forward-only schema, segregated SQL role, signing-key custody, RLS,
 * live session, doctor/branch rules, audit, restore and release gates.
 */
@RestController
@ConditionalOnProperty(name="aronexa.opd.booking.reviewed-activation",
        havingValue="true",matchIfMissing=false)
@RequestMapping("/api/v1/branches/{branchId}/opd/appointments")
public final class OpdBookingHttpController {
    private final TenantAuthorization authorization;
    private final ModuleAccessService modules;
    private final OpdRegistrationHandoffService handoff;
    private final ObjectProvider<OpdBookingCommand.RestrictedVisitTransaction> restricted;

    public OpdBookingHttpController(TenantAuthorization authorization,
            ModuleAccessService modules,
            OpdRegistrationHandoffService handoff,
            ObjectProvider<OpdBookingCommand.RestrictedVisitTransaction> restricted) {
        this.authorization=authorization;
        this.modules=modules;
        this.handoff=handoff;
        this.restricted=restricted;
    }

    // No tenant ID, patient ID or SQL/proof parameters from browser.
    public record Book(UUID registrationId, UUID doctorMembershipId,
            LocalDate visitDate, LocalTime slotStart, UUID requestId) {}
    public record CheckIn(int expectedRevision, UUID requestId) {}
    public record Failure(String code) {}

    private OpdBookingCommand command(String permission) {
        // Authentication/role must fail closed even if the reviewed SQL
        // writer has not yet been provisioned on this installation.
        authorization.requirePermission(permission);
        var writer=restricted.getIfAvailable();
        if(writer==null) throw new Unavailable();
        return new OpdBookingCommand(authorization,modules,handoff,writer);
    }

    @PostMapping
    public ResponseEntity<OpdBookingCommand.BookingReceipt> book(
            @PathVariable UUID branchId,@RequestBody Book input) {
        if(input==null)throw new OpdBookingCommand.InvalidBooking();
        var receipt=command(OpdBookingCommand.BOOK).book(new OpdBookingCommand.BookingRequest(
            branchId,input.registrationId(),input.doctorMembershipId(),
            input.visitDate(),input.slotStart(),input.requestId()));
        return ResponseEntity.status(receipt.replayed()
                ? HttpStatus.OK:HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore()).body(receipt);
    }

    @PostMapping("/{appointmentId}/check-in")
    public ResponseEntity<OpdBookingCommand.BookingReceipt> checkIn(
            @PathVariable UUID branchId,@PathVariable UUID appointmentId,
            @RequestBody CheckIn input) {
        if(input==null)throw new OpdBookingCommand.InvalidBooking();
        var receipt=command(OpdBookingCommand.CHECK_IN).checkIn(new OpdBookingCommand.CheckInRequest(
            branchId,appointmentId,input.expectedRevision(),input.requestId()));
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore()).body(receipt);
    }

    @ExceptionHandler(Unavailable.class)
    ResponseEntity<Failure> unavailable(Unavailable ignored) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .cacheControl(CacheControl.noStore())
                .body(new Failure("OPD_WRITER_NOT_ACTIVATED"));
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<Failure> denied(AccessDeniedException ignored) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .cacheControl(CacheControl.noStore())
                .body(new Failure("OPD_ACCESS_DENIED"));
    }

    @ExceptionHandler(OpdRestrictedSqlAdapter.Rejected.class)
    ResponseEntity<Failure> rejected(OpdRestrictedSqlAdapter.Rejected ignored) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .cacheControl(CacheControl.noStore())
                .body(new Failure("OPD_COMMAND_REJECTED"));
    }

    @ExceptionHandler({OpdBookingCommand.InvalidBooking.class,
            IllegalArgumentException.class,HttpMessageNotReadableException.class})
    ResponseEntity<Failure> invalid(Exception ignored) {
        return ResponseEntity.badRequest().cacheControl(CacheControl.noStore())
                .body(new Failure("INVALID_OPD_REQUEST"));
    }

    private static final class Unavailable extends RuntimeException {}
}
