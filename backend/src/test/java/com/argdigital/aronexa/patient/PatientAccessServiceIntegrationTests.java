package com.argdigital.aronexa.patient;

import com.argdigital.aronexa.auth.AuthPrincipal;
import com.argdigital.aronexa.opd.OpdRegistrationHandoffService;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * B2: synthetic-only positive object policy checks using migration-owner test
 * fixtures, plus actual PostgreSQL negative checks for both runtime DB roles.
 * IMPORTANT: the positive fixtures do NOT grant patient SQL runtime privileges.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class PatientAccessServiceIntegrationTests {
    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine")
            .withInitScript("db/test/role-bootstrap.sql")
            .withDatabaseName("aronexa_patient_access_test")
            .withUsername("aronexa_patient_access_test")
            .withPassword("ephemeral-only-test-admin");

    @Autowired JdbcTemplate admin;
    @Autowired PatientAccessService access;
    @Autowired OpdRegistrationHandoffService opdHandoff;

    record Fixture(UUID tenantA, UUID tenantB, UUID memberA, UUID memberAOther,
                   UUID memberB, UUID branchA, UUID branchOther, UUID branchB,
                   UUID registrationA, UUID registrationOther, UUID registrationB,
                   UUID patientA) {}

    @AfterEach
    void clearPrincipal() {
        SecurityContextHolder.clearContext();
    }

    private void tenant(UUID id) {
        admin.update("INSERT INTO tenant(id, code, display_name) VALUES (?,?,?)",
                id, "t_" + id.toString().substring(0, 8), "Synthetic hospital");
    }

    private UUID membership(UUID tenant) {
        UUID user = UUID.randomUUID(), member = UUID.randomUUID();
        admin.update("INSERT INTO app_user(id,username,password_hash) VALUES (?,?,?)",
                user, "u_" + user.toString().substring(0, 8), "test-only-not-an-actual-hash");
        admin.update("INSERT INTO user_tenant_membership(id,user_id,tenant_id) VALUES (?,?,?)",
                member, user, tenant);
        return member;
    }

    private UUID branch(UUID tenant, String code) {
        UUID facility = UUID.randomUUID(), branch = UUID.randomUUID();
        admin.update("""
                INSERT INTO facility(id,tenant_id,code,display_name,facility_kind)
                VALUES (?,?,?,'Synthetic facility','CLINIC')
                """, facility,tenant,"f_"+facility.toString().substring(0,8));
        admin.update("""
                INSERT INTO branch(id,tenant_id,facility_id,code,display_name)
                VALUES (?,?,?,?,'Synthetic branch')
                """, branch,tenant,facility,code);
        return branch;
    }

    private void assign(UUID tenant, UUID member, UUID branch) {
        admin.update("""
                INSERT INTO org_membership_branch_scope(tenant_id,membership_id,branch_id)
                VALUES (?,?,?)
                """, tenant,member,branch);
    }

    private UUID registration(UUID tenant, UUID branch, UUID member, UUID patient) {
        UUID reg = UUID.randomUUID();
        admin.update("""
                INSERT INTO patient_registration
                (id,tenant_id,patient_id,branch_id,registration_source,registered_by_membership_id)
                VALUES (?,?,?,?,'RECEPTION',?)
                """, reg,tenant,patient,branch,member);
        return reg;
    }

    private UUID patient(UUID tenant) {
        UUID id=UUID.randomUUID();
        admin.update("INSERT INTO patient(id,tenant_id,display_name) VALUES (?,?,?)",
                id,tenant,"Synthetic Patient");
        return id;
    }

    private Fixture fixture() {
        UUID ta=UUID.randomUUID(), tb=UUID.randomUUID();
        tenant(ta);
        tenant(tb);
        UUID ma=membership(ta), maOther=membership(ta), mb=membership(tb);
        UUID ba=branch(ta,"a_"+UUID.randomUUID().toString().substring(0,8));
        UUID bOther=branch(ta,"b_"+UUID.randomUUID().toString().substring(0,8));
        UUID bb=branch(tb,"c_"+UUID.randomUUID().toString().substring(0,8));
        assign(ta,ma,ba);
        assign(ta,maOther,bOther);
        assign(tb,mb,bb);
        UUID pa=patient(ta), pb=patient(tb);
        UUID ra=registration(ta,ba,ma,pa);
        UUID other=registration(ta,bOther,maOther,pa); // same master, different branch
        UUID rb=registration(tb,bb,mb,pb);
        return new Fixture(ta,tb,ma,maOther,mb,ba,bOther,bb,ra,other,rb,pa);
    }

    private void activateSyntheticRegistrationModule(UUID tenant) {
        // Tests intentionally emulate a future reviewed module catalog change;
        // no catalog row or entitlement is added by B2 to any real database.
        // V9 ships an unreleased catalog entry; enable it ONLY in this
        // disposable synthetic Testcontainer's migration-owner fixtures.
        admin.update("""
                UPDATE module_registry SET implemented=TRUE, released=TRUE
                WHERE code='PATIENT_REGISTRATION'
                """);
        admin.update("""
                INSERT INTO tenant_module_state
                  (tenant_id,module_code,entitled,activated,clinically_authorized)
                VALUES (?,'PATIENT_REGISTRATION',TRUE,TRUE,TRUE)
                """, tenant);
    }

    private void as(UUID tenant,UUID member,boolean forcePassword, String role,
                    String... permissions) {
        AuthPrincipal principal=new AuthPrincipal(UUID.randomUUID(),tenant,member,
                "synthetic",forcePassword,List.of(role),List.of(permissions));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal,null,List.of()));
    }

    private void asOpdBookingActor(UUID tenant,UUID membership,
                                   boolean forcedPassword,String... permissions) {
        AuthPrincipal principal=new AuthPrincipal(UUID.randomUUID(),tenant,membership,
                "synthetic_opd_actor",forcedPassword,List.of("RECEPTION"),
                List.of(permissions),UUID.randomUUID());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal,null,List.of()));
    }

    @Test
    void r110aHandoffResolvesExactPatientUnderRestrictedPostgresRoleAndAudits() {
        Fixture f=fixture();
        activateSyntheticRegistrationModule(f.tenantA());
        asOpdBookingActor(f.tenantA(),f.memberA(),false,
                "APPOINTMENT_BOOK","PATIENT_REGISTRATION_READ");
        var subject=opdHandoff.resolveForBooking(f.branchA(),f.registrationA());
        assertThat(subject.tenantId()).isEqualTo(f.tenantA());
        assertThat(subject.branchId()).isEqualTo(f.branchA());
        assertThat(subject.registrationId()).isEqualTo(f.registrationA());
        assertThat(subject.patientId()).isEqualTo(f.patientA());
        assertThat(subject.actorMembershipId()).isEqualTo(f.memberA());
        assertThat(subject.sessionId()).isNotNull();
        assertThat(admin.queryForObject("""
            SELECT count(*) FROM public.patient_registration_access_audit
            WHERE tenant_id=? AND branch_id=? AND membership_id=?
              AND registration_id=? AND action='READ'
            """,Integer.class,f.tenantA(),f.branchA(),f.memberA(),
                f.registrationA())).isOne();
    }

    @Test
    void r110aHandoffDeniesCrossBranchTenantForgedRegistrationAndMissingPermissions() {
        Fixture f=fixture();
        activateSyntheticRegistrationModule(f.tenantA());
        asOpdBookingActor(f.tenantA(),f.memberA(),false,
                "APPOINTMENT_BOOK","PATIENT_REGISTRATION_READ");
        assertThatThrownBy(()->opdHandoff.resolveForBooking(
                f.branchOther(),f.registrationOther()))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(()->opdHandoff.resolveForBooking(
                f.branchB(),f.registrationB()))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(()->opdHandoff.resolveForBooking(
                f.branchA(),f.registrationOther()))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(()->opdHandoff.resolveForBooking(
                f.branchA(),UUID.randomUUID()))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(()->opdHandoff.resolveForBooking(
                new UUID(0L,0L),f.registrationA()))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(()->opdHandoff.resolveForBooking(
                f.branchA(),null))
                .isInstanceOf(AccessDeniedException.class);

        asOpdBookingActor(f.tenantA(),f.memberA(),false,
                "PATIENT_REGISTRATION_READ");
        assertThatThrownBy(()->opdHandoff.resolveForBooking(
                f.branchA(),f.registrationA()))
                .isInstanceOf(AccessDeniedException.class);
        asOpdBookingActor(f.tenantA(),f.memberA(),false,"APPOINTMENT_BOOK");
        assertThatThrownBy(()->opdHandoff.resolveForBooking(
                f.branchA(),f.registrationA()))
                .isInstanceOf(AccessDeniedException.class);
        asOpdBookingActor(f.tenantA(),f.memberA(),true,
                "APPOINTMENT_BOOK","PATIENT_REGISTRATION_READ");
        assertThatThrownBy(()->opdHandoff.resolveForBooking(
                f.branchA(),f.registrationA()))
                .isInstanceOf(AccessDeniedException.class);

        // Old hand-constructed AuthPrincipal omits an actual bearer session.
        as(f.tenantA(),f.memberA(),false,"RECEPTION",
                "APPOINTMENT_BOOK","PATIENT_REGISTRATION_READ");
        assertThatThrownBy(()->opdHandoff.resolveForBooking(
                f.branchA(),f.registrationA()))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void r110aHandoffRechecksBranchAndModuleRevocationWithoutReusingPriorResult() {
        Fixture f=fixture();
        activateSyntheticRegistrationModule(f.tenantA());
        asOpdBookingActor(f.tenantA(),f.memberA(),false,
                "APPOINTMENT_BOOK","PATIENT_REGISTRATION_READ");
        var first=opdHandoff.resolveForBooking(f.branchA(),f.registrationA());
        assertThat(first.patientId()).isEqualTo(f.patientA());

        admin.update("""
            DELETE FROM public.org_membership_branch_scope
             WHERE tenant_id=? AND membership_id=? AND branch_id=?
            """,f.tenantA(),f.memberA(),f.branchA());
        assertThatThrownBy(()->opdHandoff.resolveForBooking(
                f.branchA(),f.registrationA()))
                .isInstanceOf(AccessDeniedException.class);

        assign(f.tenantA(),f.memberA(),f.branchA());
        assertThat(opdHandoff.resolveForBooking(
                f.branchA(),f.registrationA()).patientId()).isEqualTo(f.patientA());

        admin.update("""
            UPDATE public.tenant_module_state SET activated=false
            WHERE tenant_id=? AND module_code='PATIENT_REGISTRATION'
            """,f.tenantA());
        assertThatThrownBy(()->opdHandoff.resolveForBooking(
                f.branchA(),f.registrationA()))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void absentRegistrationModuleFailsClosedDespiteMembershipAndPermission() {
        Fixture f=fixture();
        as(f.tenantA(),f.memberA(),false,"RECEPTION","PATIENT_REGISTER","PATIENT_REGISTRATION_READ");
        assertThatThrownBy(() -> access.requireReceptionBranch(f.branchA()))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> access.findReadableRegistration(f.branchA(),f.registrationA()))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void permittedBranchAndRegistrationAreVisibleButOtherBranchesAndTenantsAreNot() {
        Fixture f=fixture();
        activateSyntheticRegistrationModule(f.tenantA());
        as(f.tenantA(),f.memberA(),false,"RECEPTION","PATIENT_REGISTER","PATIENT_REGISTRATION_READ");
        var permitted=access.requireReceptionBranch(f.branchA());
        assertThat(permitted.tenantId()).isEqualTo(f.tenantA());
        assertThat(permitted.membershipId()).isEqualTo(f.memberA());
        assertThat(permitted.branchId()).isEqualTo(f.branchA());

        var found=access.findReadableRegistration(f.branchA(),f.registrationA());
        assertThat(found).isPresent();
        assertThat(found.orElseThrow().patientId()).isEqualTo(f.patientA());
        assertThat(found.orElseThrow().registrationId()).isEqualTo(f.registrationA());
        assertThat(admin.queryForObject("""
                SELECT count(*) FROM patient_registration_access_audit
                WHERE tenant_id=? AND registration_id=? AND membership_id=?
                """, Integer.class,f.tenantA(),f.registrationA(),f.memberA())).isEqualTo(1);
        assertThat(access.findReadableRegistration(f.branchA(),f.registrationOther())).isEmpty();
        assertThat(access.findReadableRegistration(f.branchOther(),f.registrationOther())).isEmpty();
        assertThat(access.findReadableRegistration(f.branchB(),f.registrationB())).isEmpty();
        assertThat(access.findReadableRegistration(f.branchA(),UUID.randomUUID())).isEmpty();
        assertThat(access.findReadableRegistration(null,f.registrationA())).isEmpty();
    }

    @Test
    void differentMembershipCannotBorrowSameTenantBranchOrRegistration() {
        Fixture f=fixture();
        activateSyntheticRegistrationModule(f.tenantA());
        as(f.tenantA(),f.memberAOther(),false,"RECEPTION",
                "PATIENT_REGISTER","PATIENT_REGISTRATION_READ");
        assertThatThrownBy(() -> access.requireReceptionBranch(f.branchA()))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(access.findReadableRegistration(f.branchA(),f.registrationA())).isEmpty();
        assertThat(access.findReadableRegistration(f.branchOther(),f.registrationOther())).isPresent();
    }

    @Test
    void permissionAndPasswordStateCannotBeBypassedByPlatformAdminRole() {
        Fixture f=fixture();
        activateSyntheticRegistrationModule(f.tenantA());
        as(f.tenantA(),f.memberA(),false,"PLATFORM_ADMIN","ORG_READ");
        assertThatThrownBy(() -> access.requireReceptionBranch(f.branchA()))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> access.findReadableRegistration(f.branchA(),f.registrationA()))
                .isInstanceOf(AccessDeniedException.class);
        as(f.tenantA(),f.memberA(),true,"RECEPTION",
                "PATIENT_REGISTER","PATIENT_REGISTRATION_READ");
        assertThatThrownBy(() -> access.requireReceptionBranch(f.branchA()))
                .isInstanceOf(AccessDeniedException.class);
        SecurityContextHolder.clearContext();
        assertThatThrownBy(() -> access.requireReceptionBranch(f.branchA()))
                .isInstanceOf(
                        org.springframework.security.authentication.AuthenticationCredentialsNotFoundException.class);
    }

    @Test
    void membershipRevocationIsObservedWithoutCachingOldAuthorization() {
        Fixture f=fixture();
        activateSyntheticRegistrationModule(f.tenantA());
        as(f.tenantA(),f.memberA(),false,"RECEPTION",
                "PATIENT_REGISTER","PATIENT_REGISTRATION_READ");
        assertThat(access.findReadableRegistration(f.branchA(),f.registrationA())).isPresent();
        admin.update("""
                DELETE FROM org_membership_branch_scope
                WHERE tenant_id=? AND membership_id=? AND branch_id=?
                """,f.tenantA(),f.memberA(),f.branchA());
        assertThat(access.findReadableRegistration(f.branchA(),f.registrationA())).isEmpty();
        assertThatThrownBy(() -> access.requireReceptionBranch(f.branchA()))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void neitherRuntimeAccountHasPatientTablePrivilege() throws Exception {
        fixture();
        for (String[] credential : new String[][] {
                {"aronexa_app_runtime","only-for-ephemeral-test-container"},
                {"aronexa_auth_runtime","only-for-ephemeral-auth-role"}}) {
            try (Connection c=DriverManager.getConnection(postgres.getJdbcUrl(),
                    credential[0],credential[1])) {
                try (var stmt=c.createStatement()) {
                    for (String table : List.of("patient","patient_identifier",
                            "patient_contact","patient_relationship",
                            "patient_registration","patient_identity_audit")) {
                        try (var rs=stmt.executeQuery(
                                "SELECT has_table_privilege(current_user, 'public."
                                    + table + "', 'SELECT')")) {
                            assertThat(rs.next()).isTrue();
                            assertThat(rs.getBoolean(1)).isFalse();
                        }
                        try (var rs=stmt.executeQuery(
                                "SELECT has_table_privilege(current_user, 'public."
                                    + table + "', 'INSERT')")) {
                            assertThat(rs.next()).isTrue();
                            assertThat(rs.getBoolean(1)).isFalse();
                        }
                    }
                }
            }
        }
    }
}
