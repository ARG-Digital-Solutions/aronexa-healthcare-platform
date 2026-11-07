package com.argdigital.aronexa.auth;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** Authentication lifecycle only; no privileged public account creation. */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    public record LoginRequest(String tenantCode, String username, String password) {}
    public record PasswordChangeRequest(String currentPassword, String newPassword) {}
    private final AuthService service;
    private final TenantAuthorization tenantAuthorization;
    AuthController(AuthService service, TenantAuthorization tenantAuthorization) {
        this.service=service;
        this.tenantAuthorization=tenantAuthorization;
    }

    @PostMapping("/login")
    public ResponseEntity<AuthService.LoginResult> login(@RequestBody LoginRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.login(request.tenantCode(),request.username(),request.password()));
    }

    /** Preserve /me response fields without disclosing the internal session UUID. */
    public record MeResult(java.util.UUID userId, java.util.UUID tenantId,
                           java.util.UUID membershipId, String username,
                           boolean mustChangePassword, java.util.List<String> roles,
                           java.util.List<String> permissions) {}
    @GetMapping("/me")
    public ResponseEntity<MeResult> me() {
        AuthPrincipal p = tenantAuthorization.currentPrincipal();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new MeResult(p.userId(),p.tenantId(),p.membershipId(),
                        p.username(),p.mustChangePassword(),p.roles(),p.permissions()));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@AuthenticationPrincipal AuthPrincipal principal,
                                       @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization) {
        service.logout(principal,authorization.substring(7));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/password")
    public ResponseEntity<Void> password(@AuthenticationPrincipal AuthPrincipal principal,
                                         @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
                                         @RequestBody PasswordChangeRequest request) {
        service.changePassword(principal, authorization.substring(7),
                request.currentPassword(),request.newPassword());
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(AuthenticationRejected.class)
    ResponseEntity<String> rejected(AuthenticationRejected ignored) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).cacheControl(CacheControl.noStore())
                .body("Authentication failed");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<String> validation(IllegalArgumentException ignored) {
        return ResponseEntity.badRequest().cacheControl(CacheControl.noStore())
                .body("Password does not meet requirements");
    }
}
