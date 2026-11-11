package com.argdigital.aronexa.patient;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Synthetic-only signed registration capability. Unlike PostgreSQL custom
 * GUCs, the HMAC is not forgeable with a stolen patient SQL LOGIN alone.
 * The verifier key is held separately by a NOLOGIN function owner.
 *
 * Not a production-issued token, not an HTTP input, not a replacement for
 * independent staff/session/permission/tenant/branch checks at execution.
 */
public final class SyntheticRegistrationProof {
    private static final String VERSION = "ARONEXA-C2-PROOF-V3";
    private SyntheticRegistrationProof() {}

    /** Compatibility helper for isolated test key #1. The MAC still binds key ID 1. */
    public static String sign(byte[] secret, UUID tenant, UUID membership, UUID branch,
                              String normalizedName, String precision, LocalDate dateOfBirth,
                              String idempotencyHash, String payloadHash, String mrn,
                              UUID nonce, long expiresAtEpochSeconds) {
        return sign(secret, 1, tenant, membership, new UUID(0L,0L), branch, normalizedName,
                precision, dateOfBirth, idempotencyHash, payloadHash, mrn, nonce, expiresAtEpochSeconds);
    }

    /** Compatibility for existing non-HTTP cryptographic unit tests only. */
    public static String sign(byte[] secret, int keyId,
                              UUID tenant, UUID membership, UUID branch,
                              String normalizedName, String precision, LocalDate dateOfBirth,
                              String idempotencyHash, String payloadHash, String mrn,
                              UUID nonce, long expiresAtEpochSeconds) {
        return sign(secret,keyId,tenant,membership,new UUID(0L,0L),branch,normalizedName,
                precision,dateOfBirth,idempotencyHash,payloadHash,mrn,nonce,expiresAtEpochSeconds);
    }

    /** The validated bearer session ID is authenticated inside every SQL request. */
    public static String sign(byte[] secret, int keyId,
                              UUID tenant, UUID membership, UUID sessionId, UUID branch,
                              String normalizedName, String precision, LocalDate dateOfBirth,
                              String idempotencyHash, String payloadHash, String mrn,
                              UUID nonce, long expiresAtEpochSeconds) {
        if (keyId <= 0 || secret == null || secret.length < 32 || tenant == null || membership == null || sessionId == null
                || branch == null || normalizedName == null || precision == null
                || idempotencyHash == null || payloadHash == null || mrn == null || nonce == null
                || expiresAtEpochSeconds <= 0
                || normalizedName.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Invalid synthetic registration proof");
        }
        String canonical = String.join("\n",
                VERSION, Integer.toString(keyId), tenant.toString(), membership.toString(),
                sessionId.toString(), branch.toString(),
                HexFormat.of().formatHex(normalizedName.getBytes(StandardCharsets.UTF_8)),
                precision, dateOfBirth == null ? "-" : dateOfBirth.toString(),
                idempotencyHash, payloadHash, mrn, nonce.toString(),
                Long.toString(expiresAtEpochSeconds));
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", impossible);
        }
    }
}
