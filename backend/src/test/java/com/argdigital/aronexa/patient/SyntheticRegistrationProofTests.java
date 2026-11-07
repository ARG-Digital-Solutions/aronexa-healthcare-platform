package com.argdigital.aronexa.patient;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class SyntheticRegistrationProofTests {
    private static final byte[] SECRET =
            "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB".getBytes(StandardCharsets.UTF_8);

    @Test void signedPermitBindsIdentityScopePayloadMrnNonceAndExpiry() {
        UUID tenant=UUID.randomUUID(),actor=UUID.randomUUID(),branch=UUID.randomUUID(),nonce=UUID.randomUUID();
        String name="Synthetic Patient",precision="EXACT",key="a".repeat(64),payload="b".repeat(64),mrn="PAT-0123456789AB";
        LocalDate dob=LocalDate.of(1980,4,3);
        long expiry=1794000000L;
        String proof= SyntheticRegistrationProof.sign(SECRET,tenant,actor,branch,name,precision,
                dob,key,payload,mrn,nonce,expiry);
        assertThat(proof).matches("[0-9a-f]{64}");
        String secondKeyProof=SyntheticRegistrationProof.sign(SECRET,2,tenant,actor,
                branch,name,precision,dob,key,payload,mrn,nonce,expiry);
        assertThat(secondKeyProof).matches("[0-9a-f]{64}").isNotEqualTo(proof);
        assertThatThrownBy(()->SyntheticRegistrationProof.sign(SECRET,0,tenant,actor,
                branch,name,precision,dob,key,payload,mrn,nonce,expiry))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(SyntheticRegistrationProof.sign(SECRET,tenant,actor,branch,name,precision,
                dob,key,payload,mrn,nonce,expiry)).isEqualTo(proof);
        assertThat(SyntheticRegistrationProof.sign(SECRET,UUID.randomUUID(),actor,branch,name,precision,
                dob,key,payload,mrn,nonce,expiry)).isNotEqualTo(proof);
        assertThat(SyntheticRegistrationProof.sign(SECRET,tenant,UUID.randomUUID(),branch,name,precision,
                dob,key,payload,mrn,nonce,expiry)).isNotEqualTo(proof);
        assertThat(SyntheticRegistrationProof.sign(SECRET,tenant,actor,UUID.randomUUID(),name,precision,
                dob,key,payload,mrn,nonce,expiry)).isNotEqualTo(proof);
        assertThat(SyntheticRegistrationProof.sign(SECRET,tenant,actor,branch,name+" X",precision,
                dob,key,payload,mrn,nonce,expiry)).isNotEqualTo(proof);
        assertThat(SyntheticRegistrationProof.sign(SECRET,tenant,actor,branch,name,precision,
                dob.plusDays(1),key,payload,mrn,nonce,expiry)).isNotEqualTo(proof);
        assertThat(SyntheticRegistrationProof.sign(SECRET,tenant,actor,branch,name,precision,
                dob,key,payload,"PAT-0123456789AC",nonce,expiry)).isNotEqualTo(proof);
        assertThat(SyntheticRegistrationProof.sign(SECRET,tenant,actor,branch,name,precision,
                dob,key,payload,mrn,UUID.randomUUID(),expiry)).isNotEqualTo(proof);
        assertThat(SyntheticRegistrationProof.sign(SECRET,tenant,actor,branch,name,precision,
                dob,key,payload,mrn,nonce,expiry+1)).isNotEqualTo(proof);
        assertThat(SyntheticRegistrationProof.sign(SECRET,tenant,actor,branch,name,precision,
                dob,key,"c".repeat(64),mrn,nonce,expiry)).isNotEqualTo(proof);
    }

    @Test void rejectsShortSigningKeyAndInjectedControlCharacters() {
        UUID t=UUID.randomUUID(),m=UUID.randomUUID(),b=UUID.randomUUID(),n=UUID.randomUUID();
        assertThatThrownBy(()->SyntheticRegistrationProof.sign(
                new byte[4],t,m,b,"Synthetic", "UNKNOWN",null,
                "a".repeat(64),"b".repeat(64),"PAT-0123456789AB",n,1794000000L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->SyntheticRegistrationProof.sign(
                SECRET,t,m,b,"Synthetic\nPatient","UNKNOWN",null,
                "a".repeat(64),"b".repeat(64),"PAT-0123456789AB",n,1794000000L))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
