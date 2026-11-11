package com.argdigital.aronexa.offlineproof;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.security.Signature;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Base64;

/**
 * COMM-V1-T09: offline Ed25519 signature-verification PROOF ONLY.
 *
 * There is deliberately NO signing method, issuer private key, persistent
 * activation service, production entitlement integration or clock rollback
 * protection in this application. Real clinical authorization remains separate.
 */
public final class OfflineLicenseProof {
    private OfflineLicenseProof() {}

    public enum Status { ACTIVE, EXPIRED_CONTINUITY, INVALID }

    public record Decision(Status status, LocalDate expiresOn) {
        public boolean allowsLicensedExtras() {
            return status == Status.ACTIVE;
        }

        /**
         * A license must NEVER be used to hold existing clinical records hostage.
         * Authentication and clinical-role authorization are separate checks.
         */
        public boolean mustPreserveClinicalContinuity() {
            return true;
        }
    }

    private static final String HEADER = "ARONEXA|OFFLINE-PROOF|V1|";
    private static final int MAX_TOKEN_CHARS = 2048;
    private static final int SIGNATURE_BYTES = 64;

    /**
     * Public canonical format documentation for a SEPARATE external issuer.
     * Never generate/ship the issuer private key inside a Windows application.
     */
    public static byte[] canonicalPayload(String clinicId, LocalDate expiresOn) {
        if (clinicId == null || !clinicId.matches("[A-Z0-9-]{3,64}")) {
            throw new IllegalArgumentException("Invalid fictional clinic ID");
        }
        if (expiresOn == null || expiresOn.isBefore(LocalDate.of(2000, 1, 1))
                || expiresOn.isAfter(LocalDate.of(9999, 12, 31))) {
            throw new IllegalArgumentException("Invalid license expiry");
        }
        return (HEADER + clinicId + "|" + expiresOn).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Compact string: base64url(canonical UTF-8 payload) + "." +
     * base64url(Ed25519 signature). The provided public key is a DEMO trust
     * anchor; a real signed installer must pin an independently controlled key.
     */
    public static Decision verify(String token, String expectedClinicId,
                                  PublicKey issuerPublicKey, Clock clinicClock) {
        if (token == null || token.length() > MAX_TOKEN_CHARS
                || expectedClinicId == null || issuerPublicKey == null
                || clinicClock == null) {
            return new Decision(Status.INVALID, null);
        }
        try {
            String[] parts = token.split("\\.", -1);
            if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
                return new Decision(Status.INVALID, null);
            }
            byte[] payload = Base64.getUrlDecoder().decode(parts[0]);
            byte[] signatureBytes = Base64.getUrlDecoder().decode(parts[1]);
            if (signatureBytes.length != SIGNATURE_BYTES) {
                return new Decision(Status.INVALID, null);
            }
            String[] fields = new String(payload, StandardCharsets.UTF_8).split("\\|", -1);
            if (fields.length != 5 || !fields[0].equals("ARONEXA")
                    || !fields[1].equals("OFFLINE-PROOF") || !fields[2].equals("V1")
                    || !fields[3].equals(expectedClinicId)) {
                return new Decision(Status.INVALID, null);
            }
            LocalDate expires = LocalDate.parse(fields[4]);
            if (!Arrays.equals(payload, canonicalPayload(fields[3], expires))) {
                return new Decision(Status.INVALID, null);
            }
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(issuerPublicKey);
            verifier.update(payload);
            if (!verifier.verify(signatureBytes)) {
                return new Decision(Status.INVALID, null);
            }
            Status status = LocalDate.now(clinicClock).isAfter(expires)
                    ? Status.EXPIRED_CONTINUITY : Status.ACTIVE;
            return new Decision(status, expires);
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            return new Decision(Status.INVALID, null);
        }
    }
}
