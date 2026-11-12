package com.argdigital.aronexa.patient;

import com.argdigital.aronexa.auth.AuthPrincipal;
import com.argdigital.aronexa.auth.TenantAuthorization;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Internal, inert review candidate for the production registration operation.
 *
 * NOT a Spring @Service, configuration, HTTP controller, startup hook or Flyway
 * migration. There is deliberately no runtime bean instantiating this class.
 * Only the isolated PostgreSQL candidate acceptance test calls it.
 *
 * Release requires independent approval of the separately staged V10 SQL,
 * key provisioning, a safe startup gate, request admission controls and a
 * distinct clinical write HTTP endpoint. Never use with real patients now.
 */
public final class RegistrationWriteExecutor {
    public record Receipt(UUID patientId, UUID registrationId, String mrn, boolean replayed) {}

    public static final class Rejected extends RuntimeException {
        private final String code;
        public Rejected(String code) { super(code); this.code=code; }
        public String code() { return code; }
    }

    private static final class MrnCollision extends RuntimeException {}

    private final TenantAuthorization authorization;
    private final PatientSqlScope patientScope;
    private final JdbcTemplate patientJdbc;
    private final TransactionTemplate transaction;
    private final byte[] idempotencySecret;
    private final byte[] proofSecret;
    private final int proofKeyId;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public RegistrationWriteExecutor(
            TenantAuthorization authorization, PatientSqlScope patientScope,
            JdbcTemplate patientJdbc, PlatformTransactionManager patientTransactionManager,
            byte[] idempotencySecret, byte[] proofSecret, int proofKeyId, Clock clock) {
        if (authorization==null || patientScope==null || patientJdbc==null ||
                patientTransactionManager==null || idempotencySecret==null ||
                idempotencySecret.length!=32 || proofSecret==null ||
                proofSecret.length!=32 || proofKeyId<1 || clock==null) {
            throw new IllegalArgumentException("Registration writer not configured");
        }
        this.authorization=authorization;
        this.patientScope=patientScope;
        this.patientJdbc=patientJdbc;
        this.transaction=new TransactionTemplate(patientTransactionManager);
        this.idempotencySecret=idempotencySecret.clone();
        this.proofSecret=proofSecret.clone();
        this.proofKeyId=proofKeyId;
        this.clock=clock;
    }

    public Receipt register(UUID branchId, PatientRegistrationRules.Intake input,
                            String idempotencyKey) {
        // Identity is obtained exclusively from a validated database-backed
        // session principal. All privilege checks are repeated in SQL.
        AuthPrincipal actor=authorization.requirePermission("PATIENT_REGISTER");
        if (actor.sessionId()==null || branchId==null) {
            throw new AccessDeniedException("Patient scope denied");
        }
        var intake=PatientRegistrationRules.validate(input,clock);
        String keyDigest=PatientRegistrationRules.keyDigest(idempotencySecret,
                actor.tenantId(),actor.membershipId(),branchId,
                "REGISTER",idempotencyKey);
        String fingerprint=PatientRegistrationRules.payloadFingerprint(intake);

        // A unique MRN collision must rollback the ENTIRE transaction before
        // generating a different MRN. A retry for the same idempotency key
        // still produces exactly the previously committed patient and MRN.
        for(int attempt=0;attempt<3;attempt++) {
            try {
                Receipt receipt=transaction.execute(status -> {
                    patientScope.bindVerifiedStaff();
                    String mrn=PatientRegistrationRules.generateMrn(random);
                    UUID nonce=UUID.randomUUID();
                    long expiry=Instant.now(clock).plusSeconds(30).getEpochSecond();
                    String proof=RegistrationProductionProof.sign(
                            proofSecret,proofKeyId,actor.tenantId(),actor.membershipId(),
                            actor.sessionId(),branchId,intake.displayName(),
                            intake.birthDatePrecision().name(),intake.dateOfBirth(),
                            keyDigest,fingerprint,mrn,nonce,expiry);
                    try {
                        return patientJdbc.queryForObject("""
                            SELECT patient_id,registration_id,mrn,replayed
                            FROM public.aronexa_register_patient(?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                            """,
                            (rs,row)->new Receipt(
                                rs.getObject("patient_id",UUID.class),
                                rs.getObject("registration_id",UUID.class),
                                rs.getString("mrn"),rs.getBoolean("replayed")),
                            actor.tenantId(),actor.membershipId(),actor.sessionId(),branchId,
                            intake.displayName(),intake.birthDatePrecision().name(),
                            intake.dateOfBirth(),keyDigest,fingerprint,mrn,proofKeyId,
                            nonce,expiry,proof);
                    } catch (DataAccessException failedSql) {
                        String code=rootMessage(failedSql);
                        if(code.contains("REGISTRATION_MRN_COLLISION")) throw new MrnCollision();
                        if(code.contains("REGISTRATION_IDEMPOTENCY_CONFLICT")) {
                            throw new Rejected("IDEMPOTENCY_CONFLICT");
                        }
                        if(code.contains("REGISTRATION_DUPLICATE_REVIEW_REQUIRED")) {
                            throw new Rejected("DUPLICATE_REVIEW_REQUIRED");
                        }
                        if(code.contains("REGISTRATION_INVALID_INPUT")) {
                            throw new Rejected("INVALID_REGISTRATION_REQUEST");
                        }
                        if(code.contains("REGISTRATION_ACCESS_DENIED") ||
                                code.contains("REGISTRATION_PROOF_DENIED") ||
                                code.contains("REGISTRATION_SESSION_REVOKED")) {
                            throw new AccessDeniedException("Patient scope denied");
                        }
                        // Unknown database errors never expose SQL, patient
                        // payload or signer material to callers.
                        throw new Rejected("REGISTRATION_UNAVAILABLE");
                    }
                });
                if (receipt==null) throw new Rejected("REGISTRATION_UNAVAILABLE");
                return receipt;
            } catch (MrnCollision retry) {
                if (attempt==2) throw new Rejected("REGISTRATION_UNAVAILABLE");
            }
        }
        throw new Rejected("REGISTRATION_UNAVAILABLE");
    }

    private static String rootMessage(Throwable error) {
        Throwable current=error;
        while(current.getCause()!=null && current.getCause()!=current) {
            current=current.getCause();
        }
        return current.getMessage()==null ? "" : current.getMessage();
    }

    /** Test/release inspection only; never expose copies of the secrets. */
    public int proofKeyId() { return proofKeyId; }
}
