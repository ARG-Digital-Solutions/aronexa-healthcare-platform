package com.argdigital.aronexa.auth;

import com.argdigital.aronexa.organization.OrganizationAccessService;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real non-owner PostgreSQL RLS and membership/object policies. No clinical tables exist. */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class OrganizationAccessIntegrationTests {
    private static final String RUNTIME_PASSWORD="only-for-ephemeral-test-container";

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine")
            .withInitScript("db/test/role-bootstrap.sql")
            .withDatabaseName("aronexa_org_access_test")
            .withUsername("aronexa_org_access_test")
            .withPassword("ephemeral-admin-test-only");

    @Autowired JdbcTemplate admin;

    record Fixture(UUID tenantA, UUID tenantB, UUID membershipA, UUID membershipOther,
                   UUID membershipB, UUID facilityA, UUID branchA, UUID branchOther,
                   UUID branchB, UUID deptA, UUID deptOther, UUID deptB) {}

    @AfterEach
    void clearContext() { SecurityContextHolder.clearContext(); }

    private Fixture seed() {
        UUID tenantA=UUID.randomUUID(),tenantB=UUID.randomUUID();
        UUID userA=UUID.randomUUID(),userOther=UUID.randomUUID(),userB=UUID.randomUUID();
        UUID memberA=UUID.randomUUID(),memberOther=UUID.randomUUID(),memberB=UUID.randomUUID();
        UUID facilityA=UUID.randomUUID(),facilityB=UUID.randomUUID();
        UUID branchA=UUID.randomUUID(),branchOther=UUID.randomUUID(),branchB=UUID.randomUUID();
        UUID deptA=UUID.randomUUID(),deptOther=UUID.randomUUID(),deptB=UUID.randomUUID();
        tenant(tenantA); tenant(tenantB);
        member(userA, memberA, tenantA);
        member(userOther, memberOther, tenantA);
        member(userB, memberB, tenantB);
        facility(facilityA,tenantA);
        facility(facilityB,tenantB);
        branch(branchA,tenantA,facilityA,"north");
        branch(branchOther,tenantA,facilityA,"south");
        branch(branchB,tenantB,facilityB,"east");
        dept(deptA,tenantA,branchA,"opd");
        dept(deptOther,tenantA,branchOther,"lab");
        dept(deptB,tenantB,branchB,"opd");
        assignBranch(tenantA,memberA,branchA);
        assignBranch(tenantA,memberOther,branchOther);
        assignBranch(tenantB,memberB,branchB);
        assignDept(tenantA,memberA,branchA,deptA);
        assignDept(tenantA,memberOther,branchOther,deptOther);
        assignDept(tenantB,memberB,branchB,deptB);
        return new Fixture(tenantA,tenantB,memberA,memberOther,memberB,
                facilityA,branchA,branchOther,branchB,deptA,deptOther,deptB);
    }

    private void tenant(UUID id) {
        admin.update("INSERT INTO tenant(id,code,display_name) VALUES (?,?,?)",
                id,"t_"+id.toString().substring(0,8),"Synthetic clinic");
    }
    private void member(UUID user,UUID member,UUID tenant) {
        admin.update("INSERT INTO app_user(id,username,password_hash) VALUES (?,?,?)",
                user,"u_"+user.toString().substring(0,8),"synthetic-not-real-password");
        admin.update("INSERT INTO user_tenant_membership(id,user_id,tenant_id) VALUES (?,?,?)",
                member,user,tenant);
    }
    private void facility(UUID id,UUID tenant) {
        admin.update("INSERT INTO facility(id,tenant_id,code,display_name,facility_kind) VALUES (?,?,?,?,?)",
                id,tenant,"clinic","Synthetic clinic","CLINIC");
    }
    private void branch(UUID id,UUID tenant,UUID facility,String code) {
        admin.update("INSERT INTO branch(id,tenant_id,facility_id,code,display_name) VALUES (?,?,?,?,?)",
                id,tenant,facility,code,"Synthetic branch");
    }
    private void dept(UUID id,UUID tenant,UUID branch,String code) {
        admin.update("INSERT INTO department(id,tenant_id,branch_id,code,display_name) VALUES (?,?,?,?,?)",
                id,tenant,branch,code,"Synthetic department");
    }
    private void assignBranch(UUID tenant,UUID membership,UUID branch) {
        admin.update("""
                INSERT INTO org_membership_branch_scope(tenant_id,membership_id,branch_id)
                VALUES (?,?,?)
                """,tenant,membership,branch);
    }
    private void assignDept(UUID tenant,UUID membership,UUID branch,UUID dept) {
        admin.update("""
                INSERT INTO org_membership_department_scope
                  (tenant_id,membership_id,branch_id,department_id) VALUES(?,?,?,?)
                """,tenant,membership,branch,dept);
    }

    private HikariDataSource runtime() {
        HikariConfig cfg=new HikariConfig();
        cfg.setJdbcUrl(postgres.getJdbcUrl());
        cfg.setUsername("aronexa_app_runtime");
        cfg.setPassword(RUNTIME_PASSWORD);
        cfg.setMaximumPoolSize(1);
        cfg.setMinimumIdle(0);
        return new HikariDataSource(cfg);
    }
    private static AuthPrincipal principal(UUID tenant,UUID member,boolean mustChange,
                                           List<String> roles,List<String> permissions) {
        return new AuthPrincipal(UUID.randomUUID(),tenant,member,
                "synthetic",mustChange,roles,permissions);
    }
    private void authenticate(AuthPrincipal principal) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal,null,List.of()));
    }
    private static OrganizationAccessService service(JdbcTemplate runtimeJdbc) {
        TenantAuthorization auth=new TenantAuthorization();
        return new OrganizationAccessService(auth,new TenantSqlScope(auth,runtimeJdbc),runtimeJdbc);
    }

    @Test
    void assignedObjectsAndListingsAreAccessibleWithExplicitPermission() {
        Fixture f=seed();
        try(HikariDataSource pool=runtime()) {
            authenticate(principal(f.tenantA(),f.membershipA(),false,
                    List.of("RECEPTION"),List.of("ORG_READ")));
            JdbcTemplate jdbc=new JdbcTemplate(pool);
            OrganizationAccessService access=service(jdbc);
            TransactionTemplate tx=new TransactionTemplate(new DataSourceTransactionManager(pool));
            tx.executeWithoutResult(status -> {
                assertThat(access.facility(f.facilityA())).isPresent();
                assertThat(access.branch(f.branchA())).isPresent();
                assertThat(access.department(f.deptA())).isPresent();
                assertThat(access.branches()).extracting(b -> b.id()).containsExactly(f.branchA());
                assertThat(access.departments(f.branchA())).extracting(d -> d.id())
                        .containsExactly(f.deptA());
            });
        }
    }

    @Test
    void sameTenantUnassignedIdsAndCrossTenantIdsAreInvisibleIncludingLists() {
        Fixture f=seed();
        try(HikariDataSource pool=runtime()) {
            authenticate(principal(f.tenantA(),f.membershipA(),false,
                    List.of("DOCTOR"),List.of("ORG_READ")));
            JdbcTemplate jdbc=new JdbcTemplate(pool);
            OrganizationAccessService access=service(jdbc);
            TransactionTemplate tx=new TransactionTemplate(new DataSourceTransactionManager(pool));
            tx.executeWithoutResult(status -> {
                assertThat(access.branch(f.branchOther())).isEmpty();
                assertThat(access.department(f.deptOther())).isEmpty();
                assertThat(access.branch(f.branchB())).isEmpty();
                assertThat(access.department(f.deptB())).isEmpty();
                assertThat(access.facility(UUID.randomUUID())).isEmpty();
                assertThat(access.branches()).hasSize(1);
                assertThat(access.departments(f.branchOther())).isEmpty();
                assertThat(access.departments(f.branchB())).isEmpty();
                assertThat(access.department(null)).isEmpty();
            });
        }
    }

    @Test
    void differentMembershipOfSameTenantCannotBorrowOtherMembersBranch() {
        Fixture f=seed();
        try(HikariDataSource pool=runtime()) {
            JdbcTemplate jdbc=new JdbcTemplate(pool);
            OrganizationAccessService access=service(jdbc);
            TransactionTemplate tx=new TransactionTemplate(new DataSourceTransactionManager(pool));
            authenticate(principal(f.tenantA(),f.membershipOther(),false,
                    List.of("RECEPTION"),List.of("ORG_READ")));
            tx.executeWithoutResult(status -> {
                assertThat(access.branch(f.branchOther())).isPresent();
                assertThat(access.branch(f.branchA())).isEmpty();
                assertThat(access.department(f.deptA())).isEmpty();
            });
        }
    }

    @Test
    void absentPermissionAndPlatformAdminAreNotImplicitlyAuthorized() {
        Fixture f=seed();
        try(HikariDataSource pool=runtime()) {
            JdbcTemplate jdbc=new JdbcTemplate(pool);
            OrganizationAccessService access=service(jdbc);
            TransactionTemplate tx=new TransactionTemplate(new DataSourceTransactionManager(pool));
            authenticate(principal(f.tenantA(),f.membershipA(),false,
                    List.of("PLATFORM_ADMIN"),List.of()));
            tx.executeWithoutResult(status ->
                    assertThatThrownBy(() -> access.branch(f.branchA()))
                            .isInstanceOf(AccessDeniedException.class));
            authenticate(principal(f.tenantA(),f.membershipA(),true,
                    List.of("RECEPTION"),List.of("ORG_READ")));
            tx.executeWithoutResult(status ->
                    assertThatThrownBy(() -> access.branches())
                            .isInstanceOf(AccessDeniedException.class));
            SecurityContextHolder.clearContext();
            tx.executeWithoutResult(status ->
                    assertThatThrownBy(() -> access.branches())
                            .isInstanceOf(AuthenticationCredentialsNotFoundException.class));
        }
    }

    @Test
    void denyAccessWithoutTransactionAndNoTenantContextLeakBetweenSessions() {
        Fixture f=seed();
        try(HikariDataSource pool=runtime()) {
            authenticate(principal(f.tenantA(),f.membershipA(),false,
                    List.of("RECEPTION"),List.of("ORG_READ")));
            JdbcTemplate jdbc=new JdbcTemplate(pool);
            OrganizationAccessService access=service(jdbc);
            assertThatThrownBy(() -> access.branch(f.branchA()))
                    .isInstanceOf(IllegalStateException.class);
            TransactionTemplate tx=new TransactionTemplate(new DataSourceTransactionManager(pool));
            tx.executeWithoutResult(status -> assertThat(access.branch(f.branchA())).isPresent());
            tx.executeWithoutResult(status ->
                    assertThat(jdbc.queryForObject("SELECT count(*) FROM branch",Integer.class))
                            .isZero());
        }
    }

    @Test
    void databaseRejectsCrossTenantAndUnassignedDepartmentScope() {
        Fixture f=seed();
        assertThatThrownBy(() -> assignBranch(f.tenantA(),f.membershipA(),f.branchB()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> assignDept(f.tenantA(),f.membershipA(),f.branchOther(),f.deptOther()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> assignDept(f.tenantA(),f.membershipA(),f.branchA(),f.deptOther()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void runtimeDatabaseRoleCannotProvisionAssignmentsOrReadWithoutTenant() {
        Fixture f=seed();
        try(HikariDataSource pool=runtime()) {
            JdbcTemplate jdbc=new JdbcTemplate(pool);
            assertThat(jdbc.queryForObject("""
                SELECT has_table_privilege(current_user, 'org_membership_branch_scope','INSERT')
                """,Boolean.class)).isFalse();
            assertThat(jdbc.queryForObject("""
                SELECT has_table_privilege(current_user, 'org_membership_department_scope','UPDATE')
                """,Boolean.class)).isFalse();
            assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM org_membership_branch_scope
                """,Integer.class)).isZero();
        }
    }
}
