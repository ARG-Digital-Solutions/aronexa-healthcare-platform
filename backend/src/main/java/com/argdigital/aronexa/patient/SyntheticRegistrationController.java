package com.argdigital.aronexa.patient;

import java.time.LocalDate;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.*;

/** Test-profile-only contract. Does not ship an enabled patient route by default. */
@RestController
@Profile("c2-synthetic")
@RequestMapping("/api/v1/branches/{branchId}/patient-registrations")
public class SyntheticRegistrationController {
    private final SyntheticRegistrationService service;

    public SyntheticRegistrationController(SyntheticRegistrationService service) {
        this.service = service;
    }

    public record RegisterRequest(String displayName,
                                  PatientRegistrationRules.BirthDatePrecision birthDatePrecision,
                                  LocalDate dateOfBirth) {}
    public record ErrorBody(String code) {}

    @PostMapping
    public ResponseEntity<SyntheticRegistrationService.Receipt> create(
            @PathVariable UUID branchId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody RegisterRequest request) {
        if (request == null) throw new IllegalArgumentException("Invalid request");
        var result = service.register(branchId, new PatientRegistrationRules.Intake(
                request.displayName(), request.birthDatePrecision(), request.dateOfBirth()), idempotencyKey);
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore()).body(result);
    }

    @ExceptionHandler({IllegalArgumentException.class,
            org.springframework.http.converter.HttpMessageNotReadableException.class,
            org.springframework.web.bind.MissingRequestHeaderException.class})
    public ResponseEntity<ErrorBody> malformed(Exception ignored) {
        return ResponseEntity.badRequest().cacheControl(CacheControl.noStore())
                .body(new ErrorBody("INVALID_REGISTRATION_REQUEST"));
    }

    @ExceptionHandler(SyntheticRegistrationService.RegistrationRejected.class)
    public ResponseEntity<ErrorBody> rejected(SyntheticRegistrationService.RegistrationRejected failure) {
        HttpStatus status = failure.code().equals("REGISTRATION_UNAVAILABLE")
                ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.CONFLICT;
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .body(new ErrorBody(failure.code()));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorBody> forbidden(AccessDeniedException ignored) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).cacheControl(CacheControl.noStore())
                .body(new ErrorBody("REGISTRATION_DENIED"));
    }
}
