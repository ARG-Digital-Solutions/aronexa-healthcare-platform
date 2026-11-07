package com.argdigital.aronexa.patient;

import com.argdigital.aronexa.auth.AuthPrincipal;
import com.argdigital.aronexa.auth.TenantAuthorization;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * C2 disposable synthetic-profile registration only; never active in dev/prod.
 * No patient contact/guardian storage. A narrowly owned test-only SQL function
 * atomically enforces authenticated staff membership, branch scope, permission,
 * module release/entitlement and immutable actor audit.
 */
@Service
@Profile("test & c2-synthetic & !dev & !prod")
public class SyntheticRegistrationService {
    private final TenantAuthorization authorization;
    private final PatientSqlScope patientScope;
    private final JdbcTemplate patientJdbc;
    private final SecureRandom random = new SecureRandom();
    private final byte[] hmacSecret;
    private final byte[] proofSecret;
    private final int proofKeyId;

    public SyntheticRegistrationService(
            TenantAuthorization authorization, PatientSqlScope patientScope,
            @Qualifier("patientJdbcTemplate") JdbcTemplate patientJdbc,
            @Value("${aronexa.c2.synthetic-hmac-base64:}") String encodedSecret,
            @Value("${aronexa.c2.synthetic-proof-base64:}") String encodedProofSecret,
            @Value("${aronexa.c2.synthetic-proof-key-id:1}") int proofKeyId) {
        this.authorization = authorization;
        this.patientScope = patientScope;
        this.patientJdbc = patientJdbc;
        if (proofKeyId <= 0) throw new IllegalStateException("Invalid synthetic proof key ID");
        this.proofKeyId = proofKeyId;
        try {
            this.hmacSecret = Base64.getDecoder().decode(encodedSecret);
            this.proofSecret = Base64.getDecoder().decode(encodedProofSecret);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Synthetic C2 secret must be valid Base64", e);
        }
        if (this.hmacSecret.length < 32 || this.proofSecret.length < 32) {
            throw new IllegalStateException("Synthetic C2 HMAC secret must be at least 32 bytes");
        }
    }

    public record Receipt(UUID patientId, UUID registrationId, String mrn, boolean replayed) {}

    /** Every request uses one patient role transaction; no access via migration owner. */
    @Transactional(transactionManager = "patientTransactionManager")
    public Receipt register(UUID branchId, PatientRegistrationRules.Intake intake, String idempotencyKey) {
        AuthPrincipal actor = authorization.requirePermission("PATIENT_REGISTER");
        if (branchId == null) throw new AccessDeniedException("Patient scope denied");
        var v = PatientRegistrationRules.validate(intake, Clock.systemUTC());
        String keyDigest = PatientRegistrationRules.keyDigest(hmacSecret,
                actor.tenantId(), actor.membershipId(), branchId, "REGISTER", idempotencyKey);
        String payloadHash = PatientRegistrationRules.payloadFingerprint(v);
        patientScope.bindVerifiedStaff();
        // A stolen patient SQL password can spoof session GUCs. It cannot
        // independently mint this proof because that key is not granted to the
        // SQL LOGIN role. Never return or log nonce, MAC or signing material.
        String mrn = PatientRegistrationRules.generateMrn(random);
        UUID nonce = UUID.randomUUID();
        long expiry = Instant.now().plusSeconds(30).getEpochSecond();
        String proof = SyntheticRegistrationProof.sign(proofSecret, proofKeyId, actor.tenantId(),
                actor.membershipId(), branchId, v.displayName(),
                v.birthDatePrecision().name(), v.dateOfBirth(),
                keyDigest, payloadHash, mrn, nonce, expiry);
        try {
            return patientJdbc.queryForObject("""
                    SELECT patient_id,registration_id,mrn,replayed
                    FROM public.aronexa_c2_register_patient(?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """,
                    (rs, row) -> new Receipt(rs.getObject("patient_id", UUID.class),
                            rs.getObject("registration_id", UUID.class),
                            rs.getString("mrn"), rs.getBoolean("replayed")),
                    actor.tenantId(), actor.membershipId(), branchId,
                    v.displayName(), v.birthDatePrecision().name(), v.dateOfBirth(),
                    keyDigest, payloadHash, mrn, proofKeyId, nonce, expiry, proof);
        } catch (DataAccessException failure) {
            // PostgreSQL procedure emits fixed codes, never identifiers/PII.
            // Never return SQL error details or identifiers in HTTP responses.
            String message = rootMessage(failure);
            if (message.contains("C2_ACCESS_DENIED") || message.contains("C2_PROOF_DENIED"))
                throw new AccessDeniedException("Patient scope denied");
            if (message.contains("C2_IDEMPOTENCY_CONFLICT")) throw new RegistrationRejected("IDEMPOTENCY_CONFLICT");
            if (message.contains("C2_DUPLICATE_REVIEW_REQUIRED")) throw new RegistrationRejected("DUPLICATE_REVIEW_REQUIRED");
            if (message.contains("C2_INVALID_INPUT")) throw new IllegalArgumentException("Invalid registration input");
            throw new RegistrationRejected("REGISTRATION_UNAVAILABLE");
        }
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        return current.getMessage() == null ? "" : current.getMessage();
    }

    public static class RegistrationRejected extends RuntimeException {
        private final String code;
        public RegistrationRejected(String code) { super(code); this.code = code; }
        public String code() { return code; }
    }
}
