package com.argdigital.aronexa.opd;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * R1-10B2 - short-lived, session-bound HMAC format for a REVIEW-ONLY
 * PostgreSQL command verifier. Never an HTTP credential or entitlement.
 * Server and restricted SQL owner must independently control the 32-byte key;
 * no signing key, default license or automatic provisioning ships here.
 */
public final class OpdCommandProof {
    private static final DateTimeFormatter SLOT=DateTimeFormatter.ofPattern("HH:mm");
    private OpdCommandProof() { }

    public static String booking(byte[] secret,int keyId,
            UUID tenant,UUID member,UUID session,UUID branch,
            UUID registration,UUID patient,UUID doctor,LocalDate date,
            LocalTime slot,UUID requestId,UUID nonce,long expires) {
        return sign(secret,keyId,expires,
                "ARONEXA-OPD-BOOK-V1",Integer.toString(keyId),
                str(tenant),str(member),str(session),str(branch),
                str(registration),str(patient),str(doctor),
                date==null?null:date.toString(),
                slot==null?null:SLOT.format(slot),
                str(requestId),str(nonce),Long.toString(expires));
    }

    public static String checkIn(byte[] secret,int keyId,
            UUID tenant,UUID member,UUID session,UUID branch,
            UUID appointment,int expectedRevision,UUID requestId,
            UUID nonce,long expires) {
        if(expectedRevision<1 || expectedRevision==Integer.MAX_VALUE){
            throw new IllegalArgumentException("Invalid OPD revision");
        }
        return sign(secret,keyId,expires,
                "ARONEXA-OPD-CHECKIN-V1",Integer.toString(keyId),
                str(tenant),str(member),str(session),str(branch),
                str(appointment),Integer.toString(expectedRevision),
                str(requestId),str(nonce),Long.toString(expires));
    }

    private static String str(UUID value) {
        if(value==null || value.equals(new UUID(0,0))){
            throw new IllegalArgumentException("Invalid OPD identity");
        }
        return value.toString();
    }

    private static String sign(byte[] secret,int keyId,long expires,
            String... fields) {
        if(secret==null || secret.length!=32 || keyId<1 || expires<=0){
            throw new IllegalArgumentException("Invalid OPD proof key or expiry");
        }
        for(String field:fields){
            if(field==null || field.isEmpty() || field.indexOf('\n')>=0){
                throw new IllegalArgumentException("Invalid OPD proof content");
            }
        }
        try {
            Mac mac=Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret,"HmacSHA256"));
            byte[] signature=mac.doFinal(
                String.join("\n",fields).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(signature);
        } catch(GeneralSecurityException unavailable){
            throw new IllegalStateException("HMAC-SHA256 unavailable",unavailable);
        }
    }
}
