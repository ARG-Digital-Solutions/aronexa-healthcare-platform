package com.argdigital.aronexa.opd;

import com.argdigital.aronexa.auth.AuthPrincipal;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;

/**
 * R1-10B2: independently constructable, NOT a Spring bean or HTTP API.
 *
 * Executes exactly two security-definer commands against a separately
 * provisioned, nonowner PostgreSQL OPD runtime login. NO direct table writes,
 * migrations, clinical authorization, default credentials or public routes.
 *
 * The SQL functions verify the signed exact patient/visit/actor command,
 * session expiry/revocation, current branch/permission/module, and RLS before
 * committing. A SQL GUC, an earlier handoff or a browser UUID is not proof.
 * This source cannot be activated until independent review, production key
 * custody, migration approval, security/negative tests and pilot acceptance.
 */
public final class OpdRestrictedSqlAdapter
        implements OpdBookingCommand.RestrictedVisitTransaction {
    private static final String SQL_ROLE="aronexa_opd_runtime";
    private final JdbcTemplate sql;
    private final byte[] proofKey;
    private final int proofKeyId;
    private final Clock clock;

    public OpdRestrictedSqlAdapter(DataSource restrictedDataSource,
                                    byte[] proofKey,int proofKeyId,Clock clock) {
        Objects.requireNonNull(restrictedDataSource,"restrictedDataSource");
        Objects.requireNonNull(clock,"clock");
        if(proofKey==null || proofKey.length!=32 || proofKeyId<1) {
            throw new IllegalArgumentException("OPD signing configuration missing");
        }
        this.sql=new JdbcTemplate(restrictedDataSource);
        this.proofKey=proofKey.clone();
        this.proofKeyId=proofKeyId;
        this.clock=clock;
        verifyRestrictedSqlIdentity();
    }

    private void verifyRestrictedSqlIdentity() {
        Boolean authorized=sql.queryForObject("""
            SELECT current_user='aronexa_opd_runtime'
                   AND EXISTS (
                     SELECT 1 FROM pg_catalog.pg_roles r
                     WHERE r.rolname=current_user AND r.rolcanlogin
                       AND NOT r.rolsuper AND NOT r.rolbypassrls
                       AND NOT r.rolcreatedb AND NOT r.rolcreaterole
                       AND NOT r.rolreplication)
                   AND NOT has_table_privilege(current_user,
                     'opd_candidate.appointment','SELECT')
                   AND NOT has_table_privilege(current_user,
                     'opd_candidate.appointment','INSERT')
                   AND NOT has_table_privilege(current_user,
                     'opd_candidate.appointment','UPDATE')
                   AND NOT has_table_privilege(current_user,
                     'opd_candidate.appointment','DELETE')
            """,Boolean.class);
        if(!Boolean.TRUE.equals(authorized)) {
            throw new IllegalStateException(
                    "OPD restricted SQL identity/grants verification failed");
        }
    }

    @Override
    public OpdBookingCommand.BookingReceipt bookWithinAuthorizedTransaction(
            AuthPrincipal actor,
            OpdRegistrationHandoffService.VerifiedVisitSubject subject,
            OpdBookingCommand.BookingRequest request) {
        requireActor(actor);
        if(subject==null || request==null
                || !Objects.equals(actor.tenantId(),subject.tenantId())
                || !Objects.equals(actor.membershipId(),subject.actorMembershipId())
                || !Objects.equals(actor.sessionId(),subject.sessionId())
                || !Objects.equals(subject.branchId(),request.branchId())
                || !Objects.equals(subject.registrationId(),request.registrationId())) {
            throw new AccessDeniedException("OPD scope denied");
        }
        UUID createdId=UUID.randomUUID();
        UUID nonce=UUID.randomUUID();
        long expires=Instant.now(clock).plusSeconds(30).getEpochSecond();
        String proof=OpdCommandProof.booking(proofKey,proofKeyId,
                actor.tenantId(),actor.membershipId(),actor.sessionId(),
                request.branchId(),request.registrationId(),subject.patientId(),
                request.doctorMembershipId(),request.visitDate(),
                request.slotStart(),request.requestId(),createdId,nonce,expires);
        try {
            return sql.queryForObject("""
                SELECT * FROM opd_candidate.book_guarded(
                 ?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,(rs,row)->readReceipt(rs,actor.tenantId(),
                    request.branchId()),actor.tenantId(),actor.membershipId(),
                actor.sessionId(),request.branchId(),subject.registrationId(),
                subject.patientId(),request.doctorMembershipId(),request.visitDate(),
                request.slotStart(),request.requestId(),createdId,proofKeyId,
                nonce,expires,proof);
        } catch(DataAccessException denied) {
            throw new Rejected();
        }
    }

    @Override
    public OpdBookingCommand.BookingReceipt checkInWithinAuthorizedTransaction(
            AuthPrincipal actor,OpdBookingCommand.CheckInRequest request) {
        requireActor(actor);
        if(request==null || request.branchId()==null
                || request.appointmentId()==null || request.requestId()==null
                || request.expectedRevision()<1
                || request.expectedRevision()==Integer.MAX_VALUE) {
            throw new AccessDeniedException("OPD check-in denied");
        }
        UUID nonce=UUID.randomUUID();
        long expires=Instant.now(clock).plusSeconds(30).getEpochSecond();
        String proof=OpdCommandProof.checkIn(proofKey,proofKeyId,
                actor.tenantId(),actor.membershipId(),actor.sessionId(),
                request.branchId(),request.appointmentId(),
                request.expectedRevision(),request.requestId(),nonce,expires);
        try {
            return sql.queryForObject("""
                SELECT * FROM opd_candidate.checkin_guarded(
                 ?,?,?,?,?,?,?,?,?,?,?)
                """,(rs,row)->readReceipt(rs,actor.tenantId(),
                    request.branchId()),actor.tenantId(),actor.membershipId(),
                actor.sessionId(),request.branchId(),request.appointmentId(),
                request.expectedRevision(),request.requestId(),proofKeyId,
                nonce,expires,proof);
        } catch(DataAccessException denied) {
            throw new Rejected();
        }
    }

    private static OpdBookingCommand.BookingReceipt readReceipt(
            java.sql.ResultSet rs,UUID tenant,UUID branch)
            throws java.sql.SQLException {
        return new OpdBookingCommand.BookingReceipt(
                tenant,branch,rs.getObject("result_patient_id",UUID.class),
                rs.getObject("result_registration_id",UUID.class),
                rs.getObject("result_appointment_id",UUID.class),
                rs.getObject("result_doctor_id",UUID.class),
                rs.getString("result_stage"),rs.getLong("result_revision"),
                rs.getBoolean("result_replayed"));
    }

    private static void requireActor(AuthPrincipal actor) {
        if(actor==null || actor.mustChangePassword()
                || actor.tenantId()==null || actor.membershipId()==null
                || actor.sessionId()==null) {
            throw new AccessDeniedException("OPD staff session denied");
        }
    }

    public static final class Rejected extends RuntimeException {
        public Rejected(){super("OPD command unavailable or denied");}
    }
}
