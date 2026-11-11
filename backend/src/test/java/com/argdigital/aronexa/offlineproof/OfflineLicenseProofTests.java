package com.argdigital.aronexa.offlineproof;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Base64;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Ephemeral signing keys are generated in tests ONLY and never written to source. */
class OfflineLicenseProofTests {
    private static KeyPair issuer;
    private static final String CLINIC = "FICTIONAL-CLINIC-01";
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-11-11T06:30:00Z"), ZoneId.of("Asia/Kolkata"));

    @BeforeAll static void generateEphemeralIssuer() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
        issuer = generator.generateKeyPair();
    }

    private static String signed(String clinic, LocalDate expiry) throws Exception {
        byte[] payload = OfflineLicenseProof.canonicalPayload(clinic, expiry);
        return signBytes(payload);
    }

    private static String signBytes(byte[] payload) throws Exception {
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(issuer.getPrivate());
        signer.update(payload);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(payload)
                + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(signer.sign());
    }

    private static OfflineLicenseProof.Decision check(String token) {
        return OfflineLicenseProof.verify(token, CLINIC, issuer.getPublic(), CLOCK);
    }

    @Test void validSignedClinicLicenceActivatesOnlyItsDemoEntitlement() throws Exception {
        var result = check(signed(CLINIC, LocalDate.of(2027, 11, 11)));
        assertThat(result.status()).isEqualTo(OfflineLicenseProof.Status.ACTIVE);
        assertThat(result.allowsLicensedExtras()).isTrue();
        assertThat(result.mustPreserveClinicalContinuity()).isTrue();
    }

    @Test void expiryDateIsInclusiveAtClinicsLocalDay() throws Exception {
        assertThat(check(signed(CLINIC, LocalDate.of(2026, 11, 11))).status())
                .isEqualTo(OfflineLicenseProof.Status.ACTIVE);
    }

    @Test void expiredSignedLicenceKeepsClinicalContinuityPolicy() throws Exception {
        var result = check(signed(CLINIC, LocalDate.of(2026, 11, 10)));
        assertThat(result.status()).isEqualTo(OfflineLicenseProof.Status.EXPIRED_CONTINUITY);
        assertThat(result.allowsLicensedExtras()).isFalse();
        assertThat(result.mustPreserveClinicalContinuity()).isTrue();
    }

    @Test void incorrectClinicRejectedDespiteValidIssuerSignature() throws Exception {
        assertThat(check(signed("FICTIONAL-CLINIC-02", LocalDate.of(2027, 11, 11))).status())
                .isEqualTo(OfflineLicenseProof.Status.INVALID);
    }

    @Test void tamperingPayloadWithoutResigningFailsClosed() throws Exception {
        String valid = signed(CLINIC, LocalDate.of(2027, 11, 11));
        String[] parts = valid.split("\\.", -1);
        byte[] altered = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8)
                .replace("2027-11-11", "2099-11-11").getBytes(StandardCharsets.UTF_8);
        String tampered = Base64.getUrlEncoder().withoutPadding().encodeToString(altered)
                + "." + parts[1];
        assertThat(check(tampered).status()).isEqualTo(OfflineLicenseProof.Status.INVALID);
    }

    @Test void wrongIssuerKeyRejected() throws Exception {
        KeyPair another = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var decision = OfflineLicenseProof.verify(
                signed(CLINIC, LocalDate.of(2027, 11, 11)),
                CLINIC, another.getPublic(), CLOCK);
        assertThat(decision.status()).isEqualTo(OfflineLicenseProof.Status.INVALID);
    }

    @Test void malformedSignatureRejected() throws Exception {
        String valid = signed(CLINIC, LocalDate.of(2027, 11, 11));
        assertThat(check(valid.substring(0, valid.lastIndexOf('.') + 1) + "a").status())
                .isEqualTo(OfflineLicenseProof.Status.INVALID);
    }

    @Test void invalidBase64Rejected() {
        assertThat(check("%%%.$$$").status()).isEqualTo(OfflineLicenseProof.Status.INVALID);
    }

    @Test void nullLicenseRejectedWithoutThrowing() {
        assertThat(check(null).status()).isEqualTo(OfflineLicenseProof.Status.INVALID);
    }

    @Test void missingVerifierRejectedWithoutThrowing() throws Exception {
        assertThat(OfflineLicenseProof.verify(signed(CLINIC, LocalDate.now(CLOCK)),
                CLINIC, null, CLOCK).status()).isEqualTo(OfflineLicenseProof.Status.INVALID);
    }

    @Test void oversizedTokenRejected() {
        assertThat(check("A".repeat(3000)).status()).isEqualTo(OfflineLicenseProof.Status.INVALID);
    }

    @Test void extraFieldsDoNotBecomeUnintendedEntitlements() throws Exception {
        String injected = signBytes(
                ("ARONEXA|OFFLINE-PROOF|V1|" + CLINIC + "|2027-11-11|OPD")
                        .getBytes(StandardCharsets.UTF_8));
        assertThat(check(injected).status()).isEqualTo(OfflineLicenseProof.Status.INVALID);
    }

    @Test void invalidSignedDateRejected() throws Exception {
        String signed = signBytes(("ARONEXA|OFFLINE-PROOF|V1|" + CLINIC + "|2026-13-40")
                .getBytes(StandardCharsets.UTF_8));
        assertThat(check(signed).status()).isEqualTo(OfflineLicenseProof.Status.INVALID);
    }

    @Test void normalizationAndNewlineInjectionDenied() {
        assertThatThrownBy(() -> OfflineLicenseProof.canonicalPayload(
                CLINIC + "\nextra=TRUE", LocalDate.of(2027, 1, 1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OfflineLicenseProof.canonicalPayload(
                "clinic lowercase", LocalDate.of(2027, 1, 1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void canonicalPayloadHasStableByteOrder() {
        var payload = OfflineLicenseProof.canonicalPayload(CLINIC, LocalDate.of(2027, 12, 31));
        assertThat(new String(payload, StandardCharsets.UTF_8))
                .isEqualTo("ARONEXA|OFFLINE-PROOF|V1|FICTIONAL-CLINIC-01|2027-12-31");
    }

    @Test void clinicalContinuityNotHostageToInvalidLicense() {
        var decision = check("invalid-license");
        assertThat(decision.status()).isEqualTo(OfflineLicenseProof.Status.INVALID);
        assertThat(decision.allowsLicensedExtras()).isFalse();
        assertThat(decision.mustPreserveClinicalContinuity()).isTrue();
    }
}
