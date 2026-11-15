package com.argdigital.aronexa.patient;

import com.argdigital.aronexa.auth.TenantAuthorization;
import com.argdigital.aronexa.moduleregistry.ModuleAccessService;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.*;

/**
 * Default-off HTTP boundary, not installation or clinical activation approval.
 * No main-source factory supplies the optional writer. An activation flag alone
 * therefore cannot register a patient. SQL migration approval, key custody,
 * encrypted recovery and release acceptance remain separate requirements.
 */
@RestController
@ConditionalOnProperty(name="aronexa.patient.registration.reviewed-activation",
        havingValue="true",matchIfMissing=false)
@Profile("!c2-synthetic & !registration-candidate")
@RequestMapping("/api/v1/branches/{branchId}/patient-registrations")
public final class RegistrationHttpController {
    private final TenantAuthorization authorization;
    private final ModuleAccessService modules;
    private final ObjectProvider<RegistrationWriteExecutor> writers;

    public RegistrationHttpController(TenantAuthorization authorization,
            ModuleAccessService modules, ObjectProvider<RegistrationWriteExecutor> writers) {
        this.authorization=authorization;
        this.modules=modules;
        this.writers=writers;
    }

    public record Register(String displayName,
            PatientRegistrationRules.BirthDatePrecision birthDatePrecision,LocalDate dateOfBirth) {}
    public record Failure(String code) {}

    @PostMapping
    public ResponseEntity<RegistrationWriteExecutor.Receipt> create(
            @PathVariable UUID branchId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody Register input) {
        var actor=authorization.requirePermission("PATIENT_REGISTER");
        if(actor.sessionId()==null) throw new AccessDeniedException("Patient scope denied");
        modules.requireEnabled("PATIENT_REGISTRATION");
        if(input==null) throw new IllegalArgumentException();
        var writer=writers.getIfAvailable();
        if(writer==null) throw new Unavailable();
        // The existing executor and restricted SQL operation independently
        // repeat live session, branch, module and persistence authorization.
        var receipt=writer.register(branchId,new PatientRegistrationRules.Intake(
            input.displayName(),input.birthDatePrecision(),input.dateOfBirth()),idempotencyKey);
        return ResponseEntity.status(receipt.replayed()?HttpStatus.OK:HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore()).body(receipt);
    }

    @ExceptionHandler(Unavailable.class)
    ResponseEntity<Failure> unavailable(Unavailable ignored) {
        return failure(HttpStatus.SERVICE_UNAVAILABLE,"REGISTRATION_WRITER_NOT_ACTIVATED");
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<Failure> denied(AccessDeniedException ignored) {
        return failure(HttpStatus.FORBIDDEN,"REGISTRATION_DENIED");
    }

    @ExceptionHandler({IllegalArgumentException.class,MissingRequestHeaderException.class,
            HttpMessageNotReadableException.class})
    ResponseEntity<Failure> invalid(Exception ignored) {
        return failure(HttpStatus.BAD_REQUEST,"INVALID_REGISTRATION_REQUEST");
    }

    @ExceptionHandler(RegistrationWriteExecutor.Rejected.class)
    ResponseEntity<Failure> rejected(RegistrationWriteExecutor.Rejected error) {
        return switch(error.code()) {
            case "IDEMPOTENCY_CONFLICT","DUPLICATE_REVIEW_REQUIRED" ->
                failure(HttpStatus.CONFLICT,error.code());
            case "INVALID_REGISTRATION_REQUEST" ->
                failure(HttpStatus.BAD_REQUEST,"INVALID_REGISTRATION_REQUEST");
            default -> failure(HttpStatus.SERVICE_UNAVAILABLE,"REGISTRATION_UNAVAILABLE");
        };
    }

    private static ResponseEntity<Failure> failure(HttpStatus status,String code) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .body(new Failure(code));
    }
    private static final class Unavailable extends RuntimeException {}
}
