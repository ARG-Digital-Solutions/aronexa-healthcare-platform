package com.argdigital.aronexa.patient;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Proof for the REVIEW-ONLY production registration SQL candidate, not
 * an access token, patient identifier, or proof of entitlement.
 *
 * The database key is held only by the scoped NOLOGIN writer in PostgreSQL,
 * and only the application server can sign a short-lived, session-bound
 * registration request. A stolen patient SQL login alone cannot forge it.
 *
 * NOT ENABLED by any Spring bean or HTTP endpoint until migration, key
 * provisioning and independent security review are approved.
 */
public final class RegistrationProductionProof {
    private static final String VERSION = "ARONEXA-REGISTRATION-PROOF-V1";
    private RegistrationProductionProof() {}

    public static String sign(
            byte[] secret, int keyId, UUID tenant, UUID membership, UUID session,
            UUID branch, String normalizedName, String precision, LocalDate dob,
            String keyDigest, String payloadHash, String mrn,
            UUID nonce, long expiryEpochSeconds) {
        if (secret == null || secret.length != 32 || keyId < 1 ||
                tenant == null || membership == null || session == null ||
                branch == null || nonce == null || normalizedName == null ||
                normalizedName.isBlank() || precision == null ||
                keyDigest == null || payloadHash == null || mrn == null ||
                expiryEpochSeconds <= 0 ||
                normalizedName.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Invalid registration proof");
        }
        String canonical = String.join("\n",
                VERSION, Integer.toString(keyId), tenant.toString(), membership.toString(),
                session.toString(), branch.toString(),
                HexFormat.of().formatHex(normalizedName.getBytes(StandardCharsets.UTF_8)),
                precision, dob == null ? "-" : dob.toString(),
                keyDigest, payloadHash, mrn, nonce.toString(),
                Long.toString(expiryEpochSeconds));
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException unavailable) {
            throw new IllegalStateException("Required HMAC-SHA256 unavailable");
        }
    }
}
