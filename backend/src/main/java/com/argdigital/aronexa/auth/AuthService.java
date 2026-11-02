package com.argdigital.aronexa.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * P01-T04 authentication foundation. No user-provisioning API or clinical grants.
 * Password reset by verified staff and MFA are not implemented yet.
 */
@Service
public class AuthService {
    public record LoginResult(String accessToken, String tokenType, long expiresInSeconds,
                              boolean mustChangePassword) {}
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Duration TOKEN_LIFETIME = Duration.ofMinutes(30);
    private static final Duration LOCKOUT = Duration.ofMinutes(15);
    private static final int MAX_FAILED = 5;

    private final AuthRepository repository;
    private final PasswordEncoder encoder;
    AuthService(AuthRepository repository, PasswordEncoder encoder) {
        this.repository = repository;
        this.encoder = encoder;
    }

    @Transactional(transactionManager = "authTransactionManager", noRollbackFor = AuthenticationRejected.class)
    public LoginResult login(String tenant, String username, String password) {
        String tenantCode = normalize(tenant), login = normalize(username);
        if (!tenantCode.matches("[a-z][a-z0-9_-]{1,47}") ||
            login.length() < 3 || login.length() > 120 || password == null ||
            password.isBlank() || password.length() > 256) throw new AuthenticationRejected();

        var account = repository.lookupForLogin(tenantCode, login)
                .orElseThrow(AuthenticationRejected::new);
        if (!account.enabled() || !account.memberEnabled() ||
            (account.lockedUntil() != null && account.lockedUntil().isAfter(Instant.now()))) {
            repository.audit(account.userId(), account.tenantId(), "LOGIN_REJECTED");
            throw new AuthenticationRejected();
        }
        if (!encoder.matches(password, account.passwordHash())) {
            int failures = account.failedLogins() + 1;
            repository.failedLogin(account.userId(), failures,
                    failures >= MAX_FAILED ? Instant.now().plus(LOCKOUT) : null);
            repository.audit(account.userId(), account.tenantId(),
                    failures >= MAX_FAILED ? "ACCOUNT_LOCKED" : "LOGIN_REJECTED");
            throw new AuthenticationRejected();
        }
        repository.clearFailedLogins(account.userId());
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        repository.storeSession(account.membershipId(),account.tenantId(),
                sha256(rawToken),Instant.now().plus(TOKEN_LIFETIME));
        repository.audit(account.userId(),account.tenantId(),"LOGIN_SUCCESS");
        return new LoginResult(rawToken,"Bearer",TOKEN_LIFETIME.toSeconds(),
                account.mustChangePassword());
    }

    public AuthPrincipal authenticate(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) {
            throw new AuthenticationRejected();
        }
        return repository.lookupSession(sha256(token),Instant.now())
                .orElseThrow(AuthenticationRejected::new);
    }

    @Transactional(transactionManager = "authTransactionManager")
    public void logout(AuthPrincipal principal, String token) {
        repository.revokeSession(sha256(token));
        repository.audit(principal.userId(),principal.tenantId(),"LOGOUT");
    }

    @Transactional
    public void changePassword(AuthPrincipal principal, String token,
                               String currentPassword,String newPassword) {
        if (currentPassword == null || newPassword == null ||
                newPassword.length() < 12 || newPassword.length() > 128 ||
                newPassword.isBlank() || newPassword.equals(currentPassword)) {
            throw new IllegalArgumentException("New password must differ and contain 12 to 128 characters");
        }
        String hash = repository.passwordHash(principal.userId())
                .orElseThrow(AuthenticationRejected::new);
        if (!encoder.matches(currentPassword, hash)) throw new AuthenticationRejected();
        repository.replacePassword(principal.userId(),encoder.encode(newPassword));
        repository.revokeAllUserSessions(principal.userId());
        repository.audit(principal.userId(),principal.tenantId(),"PASSWORD_CHANGED");
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
