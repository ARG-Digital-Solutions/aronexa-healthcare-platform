package com.argdigital.aronexa.encounter;

import com.argdigital.aronexa.auth.AuthPrincipal;
import com.argdigital.aronexa.auth.TenantAuthorization;
import com.argdigital.aronexa.encounter.EncounterDraftCommandCoordinator.AuthorizedDraftTransaction;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static com.argdigital.aronexa.encounter.EncounterDraftContract.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Real restricted-role SQL writes on a disposable PostgreSQL 17 container.
 * Simulated authenticated identity here is NOT a production login or full
 * auth_session token verification. No public encounter API is enabled.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EncounterAuthenticatedPersistenceProofTests {
    private static final String PROOF_PASSWORD = "only-for-disposable-encounter-test";

    @Container @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine")
            .withInitScript("db/test/role-bootstrap.sql")
            .withDatabaseName("aronexa_auth_encounter_disposable_test")
            .withUsername("aronexa_auth_encounter_disposable_test")
            .withPassword("ephemeral-migrator-only");

    @Autowired JdbcTemplate admin;

    @BeforeAll
    void initializeDisposableOnly() throws Exception {
        try (Connection c = admin.getDataSource().getConnection()) {
            ScriptUtils.executeSqlScript(c, new ClassPathResource("db/test/encounter-proof.sql"));
            ScriptUtils.executeSqlScript(c, new ClassPathResource("db/test/encounter-command-access-proof.sql"));
        }
        // Test-only OPD release state. Real deployment remains disabled.
        admin.update("UPDATE public.module_registry SET implemented=TRUE,released=TRUE WHERE code='OPD'");
    }

    record Fixture(UUID tenant, UUID branch, UUID otherBranch,
                   UUID patient, UUID encounter, UUID member, UUID user,
                   UUID otherMember, UUID otherUser) {}

    private Fixture seed() {
        UUID tenant = UUID.randomUUID(), facility = UUID.randomUUID();
        UUID branch = UUID.randomUUID(), otherBranch = UUID.randomUUID();
        UUID doctor = UUID.randomUUID(), user = UUID.randomUUID();
        UUID otherDoctor = UUID.randomUUID(), otherUser = UUID.randomUUID();
        UUID patient = UUID.randomUUID(), encounter = UUID.randomUUID();
        admin.update("INSERT INTO public.tenant(id,code,display_name) VALUES(?,?,?)",
                tenant,"s_" + tenant.toString().substring(0,10),"Fictional tenant");
        admin.update("""
                INSERT INTO public.facility(id,tenant_id,code,display_name,facility_kind)
                VALUES(?,?,?,'Fictional facility','CLINIC')
                """, facility,tenant,"f_"+facility.toString().substring(0,8));
        for (UUID b : new UUID[]{branch,otherBranch}) {
            admin.update("""
                    INSERT INTO public.branch(id,tenant_id,facility_id,code,display_name)
                    VALUES(?,?,?,?,'Fictional branch')
                    """, b,tenant,facility,"b_"+b.toString().substring(0,8));
        }
        for (UUID[] pair : new UUID[][]{{user,doctor},{otherUser,otherDoctor}}) {
            admin.update("""
                    INSERT INTO public.app_user(id,username,password_hash,must_change_password)
                    VALUES(?,?,?,FALSE)
                    """, pair[0],"user_"+pair[0].toString().substring(0,8),
                    "synthetic-test-hash-not-an-account");
            admin.update("""
                    INSERT INTO public.user_tenant_membership(id,user_id,tenant_id)
                    VALUES(?,?,?)
                    """, pair[1],pair[0],tenant);
        }
        admin.update("""
                INSERT INTO public.org_membership_branch_scope
                (tenant_id,membership_id,branch_id) VALUES(?,?,?)
                """,tenant,doctor,branch);
        admin.update("""
                INSERT INTO public.patient(id,tenant_id,display_name)
                VALUES(?,?,'FICTIONAL ONLY')
                """,patient,tenant);
        admin.update("""
                INSERT INTO encounter_proof.encounter_draft
                (id,tenant_id,branch_id,patient_id,doctor_membership_id)
                VALUES(?,?,?,?,?)
                """,encounter,tenant,branch,patient,doctor);
        admin.update("""
                INSERT INTO public.tenant_module_state
                (tenant_id,module_code,entitled,activated,clinically_authorized)
                VALUES(?,'OPD',TRUE,TRUE,TRUE)
                """,tenant);
        return new Fixture(tenant,branch,otherBranch,patient,encounter,doctor,user,otherDoctor,otherUser);
    }

    private AuthPrincipal login(Fixture f) {
        return login(f.tenant(),f.member(),f.user());
    }

    private AuthPrincipal login(UUID tenant, UUID member, UUID user) {
        AuthPrincipal actor = new AuthPrincipal(user,tenant,member,"fictional",false,
                List.of("DOCTOR"),List.of(EncounterDraftCommandCoordinator.DRAFT_PERMISSION));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(actor,null,List.of()));
        return actor;
    }

    @AfterEach void clearIdentity() { SecurityContextHolder.clearContext(); }

    private EncounterDraftCommandCoordinator coordinator() {
        return new EncounterDraftCommandCoordinator(new TenantAuthorization(),new ProofStore());
    }

    /** Proof-only JDBC adapter, never instantiated as a Spring production bean. */
    private final class ProofStore implements AuthorizedDraftTransaction {
        @Override
        public Draft change(AuthPrincipal actor, UUID encounterId, UnaryOperator<Draft> command) {
            try (Connection c=DriverManager.getConnection(
                    postgres.getJdbcUrl(),"aronexa_encounter_proof",PROOF_PASSWORD)) {
                c.setAutoCommit(false);
                try {
                    bind(c,"aronexa.tenant_id",actor.tenantId());
                    bind(c,"aronexa.membership_id",actor.membershipId());
                    bind(c,"aronexa.user_id",actor.userId());
                    bind(c,"aronexa.doctor_membership_id",actor.membershipId());
                    if (!liveMember(c,actor) || !moduleEnabled(c)) {
                        throw new AccessDeniedException("Encounter access denied");
                    }
                    // Branch is derived from CURRENT DB membership assignments,
                    // never accepted from an HTTP field or hardcoded to one branch.
                    List<UUID> branchIds = new ArrayList<>();
                    try (PreparedStatement p=c.prepareStatement("""
                            SELECT branch_id FROM public.org_membership_branch_scope
                            WHERE tenant_id=? AND membership_id=?
                            """)) {
                        p.setObject(1,actor.tenantId());
                        p.setObject(2,actor.membershipId());
                        try(ResultSet r=p.executeQuery()) {
                            while (r.next()) branchIds.add(r.getObject(1,UUID.class));
                        }
                    }
                    Draft existing = null;
                    for (UUID branchId : branchIds) {
                        bind(c,"aronexa.branch_id",branchId);
                        try (PreparedStatement p=c.prepareStatement("""
                                SELECT id,tenant_id,branch_id,patient_id,doctor_membership_id,
                                       revision,stage,note,rx_draft_ref,bill_draft_ref
                                FROM encounter_proof.encounter_draft
                                WHERE id=? AND tenant_id=? AND doctor_membership_id=?
                                FOR UPDATE
                                """)) {
                            p.setObject(1,encounterId);
                            p.setObject(2,actor.tenantId());
                            p.setObject(3,actor.membershipId());
                            try (ResultSet r=p.executeQuery()) {
                                if (r.next()) {
                                    Scope scope=new Scope(r.getObject("tenant_id",UUID.class),
                                            r.getObject("branch_id",UUID.class),
                                            r.getObject("patient_id",UUID.class),
                                            r.getObject("id",UUID.class),
                                            r.getObject("doctor_membership_id",UUID.class));
                                    existing=new Draft(scope,Stage.valueOf(r.getString("stage")),
                                            r.getLong("revision"),r.getString("note"),
                                            r.getObject("rx_draft_ref",UUID.class),
                                            r.getObject("bill_draft_ref",UUID.class));
                                }
                            }
                        }
                        if (existing!=null) break;
                    }
                    if (existing==null) throw new AccessDeniedException("Encounter access denied");
                    // Revalidation and the row lock are in the *same* SQL transaction.
                    Draft updated=command.apply(existing);
                    try (PreparedStatement p=c.prepareStatement("""
                            UPDATE encounter_proof.encounter_draft
                            SET revision=?,stage=?,note=?,rx_draft_ref=?,bill_draft_ref=?
                            WHERE id=? AND tenant_id=? AND revision=?
                            """)) {
                        p.setLong(1,updated.revision());
                        p.setString(2,updated.stage().name());
                        p.setString(3,updated.consultationNote());
                        p.setObject(4,updated.rxDraftRef());
                        p.setObject(5,updated.billDraftRef());
                        p.setObject(6,encounterId);
                        p.setObject(7,actor.tenantId());
                        p.setLong(8,existing.revision());
                        if (p.executeUpdate()!=1) throw new IllegalStateException("Stale draft revision");
                    }
                    try (PreparedStatement p=c.prepareStatement("""
                            INSERT INTO encounter_proof.encounter_event
                            (id,tenant_id,branch_id,doctor_membership_id,
                             encounter_id,revision,action)
                            VALUES(?,?,?,?,?,?,?)
                            """)) {
                        p.setObject(1,UUID.randomUUID());
                        p.setObject(2,actor.tenantId());
                        p.setObject(3,existing.scope().branchId());
                        p.setObject(4,actor.membershipId());
                        p.setObject(5,encounterId);
                        p.setLong(6,updated.revision());
                        String action=switch(updated.stage()) {
                            case CONSULTATION_DRAFT -> "CONSULT_DRAFT";
                            case RX_DRAFT_LINKED -> "LINK_RX";
                            case BILL_DRAFT_LINKED -> "LINK_BILL";
                            case CHECKED_IN -> throw new IllegalStateException("Invalid draft update");
                        };
                        p.setString(7,action);
                        if (p.executeUpdate()!=1) throw new IllegalStateException("Missing audit");
                    }
                    c.commit();
                    return updated;
                } catch (RuntimeException | SQLException error) {
                    c.rollback();
                    throw error;
                }
            } catch(SQLException failure) {
                // Deliberately do not expose SQL, clinical notes or role settings.
                throw new IllegalStateException("Disposable encounter SQL rejected",failure);
            }
        }

        private boolean liveMember(Connection c, AuthPrincipal actor) throws SQLException {
            try (PreparedStatement p=c.prepareStatement("""
                    SELECT 1 FROM public.user_tenant_membership m
                    JOIN public.app_user u ON u.id=m.user_id
                    WHERE m.id=? AND m.tenant_id=? AND m.user_id=?
                      AND m.enabled AND u.enabled AND NOT u.must_change_password
                    """)) {
                p.setObject(1,actor.membershipId());
                p.setObject(2,actor.tenantId());
                p.setObject(3,actor.userId());
                try(ResultSet r=p.executeQuery()){return r.next();}
            }
        }

        private boolean moduleEnabled(Connection c) throws SQLException {
            try (PreparedStatement p=c.prepareStatement("""
                    SELECT 1 FROM public.tenant_module_state s
                    JOIN public.module_registry r ON r.code=s.module_code
                    WHERE s.module_code='OPD' AND r.implemented AND r.released
                      AND s.entitled AND s.activated
                      AND (NOT r.requires_clinical_authorization OR s.clinically_authorized)
                    """)) {
                try(ResultSet r=p.executeQuery()){return r.next();}
            }
        }

        private void bind(Connection c,String key, UUID id) throws SQLException {
            try(PreparedStatement p=c.prepareStatement("SELECT set_config(?, ?, true)")) {
                p.setString(1,key);p.setString(2,id.toString());
                try(ResultSet ignored=p.executeQuery()){ }
            }
        }
    }

    private int committedEvents(UUID encounter) {
        return admin.queryForObject("""
                SELECT count(*) FROM encounter_proof.encounter_event WHERE encounter_id=?
                """,Integer.class,encounter);
    }

    @Test void authorizedDoctorCanPersistDraftAndMetadataAuditAtomically() {
        Fixture f=seed();login(f);
        Draft draft=coordinator().saveConsultation(f.encounter(),0,"Fictional note");
        assertThat(draft.stage()).isEqualTo(Stage.CONSULTATION_DRAFT);
        assertThat(draft.revision()).isEqualTo(1);
        assertThat(committedEvents(f.encounter())).isEqualTo(1);
    }

    @Test void noAuthenticatedPrincipalCannotWrite() {
        Fixture f=seed();
        assertThatThrownBy(() -> coordinator().saveConsultation(f.encounter(),0,"notes"))
                .isInstanceOf(org.springframework.security.core.AuthenticationException.class);
        assertThat(committedEvents(f.encounter())).isZero();
    }

    @Test void wrongDoctorOrTenantCannotReadOrWrite() {
        Fixture f=seed();login(f.tenant(),f.otherMember(),f.otherUser());
        assertThatThrownBy(() -> coordinator().saveConsultation(f.encounter(),0,"notes"))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(committedEvents(f.encounter())).isZero();
        login(UUID.randomUUID(),f.member(),f.user());
        assertThatThrownBy(() -> coordinator().saveConsultation(f.encounter(),0,"notes"))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test void removingBranchAssignmentRevokesNextCommand() {
        Fixture f=seed();login(f);
        coordinator().saveConsultation(f.encounter(),0,"Fictional note");
        admin.update("""
                DELETE FROM public.org_membership_branch_scope
                WHERE tenant_id=? AND membership_id=? AND branch_id=?
                """,f.tenant(),f.member(),f.branch());
        assertThatThrownBy(() -> coordinator().saveConsultation(f.encounter(),1,"New fictional note"))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(committedEvents(f.encounter())).isEqualTo(1);
    }

    @Test void disablingOpdModuleRevokesNextCommand() {
        Fixture f=seed();login(f);
        coordinator().saveConsultation(f.encounter(),0,"Fictional note");
        admin.update("""
                UPDATE public.tenant_module_state SET activated=FALSE
                WHERE tenant_id=? AND module_code='OPD'
                """,f.tenant());
        assertThatThrownBy(() -> coordinator().saveConsultation(f.encounter(),1,"Second note"))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(committedEvents(f.encounter())).isEqualTo(1);
    }

    @Test void disablingMembershipRevokesNextCommand() {
        Fixture f=seed();login(f);
        admin.update("UPDATE public.user_tenant_membership SET enabled=FALSE WHERE id=?",f.member());
        assertThatThrownBy(() -> coordinator().saveConsultation(f.encounter(),0,"notes"))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(committedEvents(f.encounter())).isZero();
    }

    @Test void disablingUserRevokesNextCommand() {
        Fixture f=seed();login(f);
        admin.update("UPDATE public.app_user SET enabled=FALSE WHERE id=?",f.user());
        assertThatThrownBy(() -> coordinator().saveConsultation(f.encounter(),0,"notes"))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(committedEvents(f.encounter())).isZero();
    }

    @Test void staleVersionCannotGenerateSecondAuditOrOverwrite() {
        Fixture f=seed();login(f);
        coordinator().saveConsultation(f.encounter(),0,"First fictional note");
        assertThatThrownBy(() -> coordinator().saveConsultation(f.encounter(),0,"Stale note"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Stale");
        assertThat(committedEvents(f.encounter())).isEqualTo(1);
        assertThat(admin.queryForObject("""
                SELECT note FROM encounter_proof.encounter_draft WHERE id=?
                """,String.class,f.encounter())).isEqualTo("First fictional note");
    }

    @Test void wrongBranchAssignmentDoesNotExposeOtherBranchEncounter() {
        Fixture f=seed();login(f);
        admin.update("""
                DELETE FROM public.org_membership_branch_scope
                WHERE tenant_id=? AND membership_id=? AND branch_id=?
                """,f.tenant(),f.member(),f.branch());
        admin.update("""
                INSERT INTO public.org_membership_branch_scope
                (tenant_id,membership_id,branch_id) VALUES(?,?,?)
                """,f.tenant(),f.member(),f.otherBranch());
        assertThatThrownBy(() -> coordinator().saveConsultation(f.encounter(),0,"notes"))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(committedEvents(f.encounter())).isZero();
    }

    @Test void auditTableNeverStoresClinicalNoteAndCannotBeRewrittenByRuntime() throws Exception {
        Fixture f=seed();login(f);
        coordinator().saveConsultation(f.encounter(),0,"FICTIONAL-PRIVATE-NOTE");
        assertThat(admin.queryForObject("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_schema='encounter_proof' AND table_name='encounter_event'
                  AND column_name IN ('note','clinical_text','patient_name','payload')
                """,Integer.class)).isZero();
        assertThat(admin.queryForObject("""
                SELECT has_table_privilege('aronexa_encounter_proof',
                        'encounter_proof.encounter_event','UPDATE')
                """,Boolean.class)).isFalse();
    }
}
