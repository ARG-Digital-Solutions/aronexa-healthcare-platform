package com.argdigital.aronexa.opd;

import static org.assertj.core.api.Assertions.*;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OpdCommandProofTests {
    private static byte[] key() {
        byte[] bytes=new byte[32];
        java.util.Arrays.fill(bytes,(byte)7);
        return bytes;
    }

    @Test
    void identicalCanonicalSignedBookingIsStableAndPatientOrVisitChangeAltersProof() {
        UUID tenant=UUID.randomUUID(),member=UUID.randomUUID(),
            session=UUID.randomUUID(),branch=UUID.randomUUID(),
            registration=UUID.randomUUID(),patient=UUID.randomUUID(),
            doctor=UUID.randomUUID(),request=UUID.randomUUID(),
            appointment=UUID.randomUUID(),nonce=UUID.randomUUID();
        String expected=OpdCommandProof.booking(key(),1,tenant,member,session,
             branch,registration,patient,doctor,LocalDate.of(2026,11,13),
             LocalTime.of(10,15),request,appointment,nonce,1234567890L);
        assertThat(expected).matches("[0-9a-f]{64}");
        assertThat(OpdCommandProof.booking(key(),1,tenant,member,session,
             branch,registration,patient,doctor,LocalDate.of(2026,11,13),
             LocalTime.of(10,15),request,appointment,nonce,1234567890L))
             .isEqualTo(expected);
        assertThat(OpdCommandProof.booking(key(),1,tenant,member,session,
             branch,registration,UUID.randomUUID(),doctor,LocalDate.of(2026,11,13),
             LocalTime.of(10,15),request,appointment,nonce,1234567890L))
             .isNotEqualTo(expected);
        assertThat(OpdCommandProof.booking(key(),1,tenant,member,session,
             branch,registration,patient,doctor,LocalDate.of(2026,11,13),
             LocalTime.of(10,15),request,UUID.randomUUID(),nonce,1234567890L))
             .isNotEqualTo(expected);
    }

    @Test
    void checkinBindsExactSessionAndExpectedRevision() {
        UUID tenant=UUID.randomUUID(),member=UUID.randomUUID(),
             session=UUID.randomUUID(),branch=UUID.randomUUID(),
             appointment=UUID.randomUUID(),request=UUID.randomUUID(),
             nonce=UUID.randomUUID();
        String proof=OpdCommandProof.checkIn(key(),1,tenant,member,session,
            branch,appointment,1,request,nonce,1234567890L);
        assertThat(OpdCommandProof.checkIn(key(),1,tenant,member,session,
            branch,appointment,2,request,nonce,1234567890L)).isNotEqualTo(proof);
        assertThat(OpdCommandProof.checkIn(key(),1,tenant,member,UUID.randomUUID(),
            branch,appointment,1,request,nonce,1234567890L)).isNotEqualTo(proof);
    }

    @Test
    void missingKeyAndInvalidOrOverflowRevisionFailClosed() {
        UUID tenant=UUID.randomUUID(),member=UUID.randomUUID(),
             session=UUID.randomUUID(),branch=UUID.randomUUID(),
             appointment=UUID.randomUUID(),request=UUID.randomUUID(),
             nonce=UUID.randomUUID();
        assertThatThrownBy(()->OpdCommandProof.checkIn(null,1,tenant,member,
            session,branch,appointment,1,request,nonce,1000L))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->OpdCommandProof.checkIn(key(),1,tenant,member,
            session,branch,appointment,Integer.MAX_VALUE,request,nonce,1000L))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->OpdCommandProof.checkIn(key(),1,tenant,member,
            null,branch,appointment,1,request,nonce,1000L))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
