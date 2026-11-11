package com.argdigital.aronexa.patient;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class PatientRegistrationRulesTests {
    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-11-07T00:00:00Z"), ZoneOffset.UTC);
    private static final byte[] SECRET = "only-for-synthetic-test-secret-key-32-plus-bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    @Test void unknownBirthDateIsAllowed() {
        var v = PatientRegistrationRules.validate(new PatientRegistrationRules.Intake(
                "  Synthetic   Person ", PatientRegistrationRules.BirthDatePrecision.UNKNOWN, null), FIXED);
        assertThat(v.displayName()).isEqualTo("Synthetic Person");
        assertThat(v.normalizedMatchName()).isEqualTo("synthetic person");
    }

    @Test void futureDateAndUnknownWithDateAreRejected() {
        assertThatThrownBy(() -> PatientRegistrationRules.validate(
                new PatientRegistrationRules.Intake("Synthetic Person",
                        PatientRegistrationRules.BirthDatePrecision.EXACT,
                        LocalDate.of(2026, 11, 8)), FIXED)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PatientRegistrationRules.validate(
                new PatientRegistrationRules.Intake("Synthetic Person",
                        PatientRegistrationRules.BirthDatePrecision.UNKNOWN,
                        LocalDate.of(2020, 1, 1)), FIXED)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void invalidNameCannotPass() {
        for (String name : new String[] {"", " ", "<script>", "A", "12345"}) {
            assertThatThrownBy(() -> PatientRegistrationRules.validate(
                    new PatientRegistrationRules.Intake(name,
                            PatientRegistrationRules.BirthDatePrecision.UNKNOWN, null), FIXED))
                    .as("invalid name").isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test void duplicateSignalsDoNotInferIdentityFromSharedPhoneOrAutoMerge() {
        var person = PatientRegistrationRules.validate(new PatientRegistrationRules.Intake(
                "Synthetic Person", PatientRegistrationRules.BirthDatePrecision.EXACT,
                LocalDate.of(1990, 1, 1)), FIXED);
        var same = PatientRegistrationRules.validate(new PatientRegistrationRules.Intake(
                " SYNTHETIC  PERSON ", PatientRegistrationRules.BirthDatePrecision.UNKNOWN, null), FIXED);
        var distinct = PatientRegistrationRules.validate(new PatientRegistrationRules.Intake(
                "Synthetic Person", PatientRegistrationRules.BirthDatePrecision.EXACT,
                LocalDate.of(1999, 1, 1)), FIXED);
        assertThat(PatientRegistrationRules.requiresManualDuplicateReview(person, same)).isTrue();
        assertThat(PatientRegistrationRules.requiresManualDuplicateReview(person, distinct)).isFalse();
    }

    @Test void generatedMrnsMeetOpaqueFormatAndV8Length() {
        var random = new SecureRandom();
        HashSet<String> generated = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            String mrn = PatientRegistrationRules.generateMrn(random);
            assertThat(mrn).matches("PAT-[0-9A-HJKMNP-TV-Z]{12}");
            assertThat(mrn.length()).isLessThanOrEqualTo(48);
            generated.add(mrn);
        }
        assertThat(generated).hasSize(1000);
    }

    @Test void idempotencyScopeSeparatesTenantActorBranchAndPayload() {
        var tenant = UUID.randomUUID();
        var actor = UUID.randomUUID();
        var branch = UUID.randomUUID();
        String key = "SyntheticClientKey_12345678901234567890";
        String digest = PatientRegistrationRules.keyDigest(SECRET, tenant, actor, branch, "REGISTER", key);
        assertThat(digest).matches("[0-9a-f]{64}");
        assertThat(PatientRegistrationRules.keyDigest(SECRET, tenant, actor, branch, "REGISTER", key))
                .isEqualTo(digest);
        assertThat(PatientRegistrationRules.keyDigest(SECRET, UUID.randomUUID(), actor, branch, "REGISTER", key))
                .isNotEqualTo(digest);
        assertThat(PatientRegistrationRules.keyDigest(SECRET, tenant, UUID.randomUUID(), branch, "REGISTER", key))
                .isNotEqualTo(digest);
        assertThat(PatientRegistrationRules.keyDigest(SECRET, tenant, actor, UUID.randomUUID(), "REGISTER", key))
                .isNotEqualTo(digest);
        assertThatThrownBy(() -> PatientRegistrationRules.keyDigest(SECRET, tenant, actor, branch,
                "REGISTER", "short")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void modifiedPayloadChangesFingerprint() {
        var a = PatientRegistrationRules.validate(new PatientRegistrationRules.Intake(
                "Synthetic One", PatientRegistrationRules.BirthDatePrecision.UNKNOWN, null), FIXED);
        var b = PatientRegistrationRules.validate(new PatientRegistrationRules.Intake(
                "Synthetic Two", PatientRegistrationRules.BirthDatePrecision.UNKNOWN, null), FIXED);
        assertThat(PatientRegistrationRules.payloadFingerprint(a)).matches("[0-9a-f]{64}");
        assertThat(PatientRegistrationRules.payloadFingerprint(a))
                .isNotEqualTo(PatientRegistrationRules.payloadFingerprint(b));
    }
}
