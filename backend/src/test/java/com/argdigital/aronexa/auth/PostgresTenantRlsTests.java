package com.argdigital.aronexa.auth;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import java.util.List;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Real PostgreSQL test via the *non-owner non-BYPASSRLS runtime login*.
 * Tests table RLS, not yet complete authenticated clinical resource policy.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class PostgresTenantRlsTests {
    private static final String RUNTIME_PASSWORD = "only-for-ephemeral-test-container";

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine")
            .withInitScript("db/test/role-bootstrap.sql")
            .withDatabaseName("aronexa_rls_test")
            .withUsername("aronexa_rls_test")
            .withPassword("ephemeral-admin-test-only");

    @Autowired JdbcTemplate admin;
    @Autowired PasswordEncoder passwordEncoder;

    record Fixture(UUID tenantA, UUID tenantB, UUID facilityA, UUID facilityB,
                   UUID branchA, UUID branchB, UUID departmentA, UUID departmentB) {}

    private Fixture seed() {
        UUID a=UUID.randomUUID(), b=UUID.randomUUID();
        UUID fa=UUID.randomUUID(), fb=UUID.randomUUID();
        UUID ba=UUID.randomUUID(), bb=UUID.randomUUID();
        UUID da=UUID.randomUUID(), db=UUID.randomUUID();
        admin.update("INSERT INTO tenant(id,code,display_name) VALUES (?,?,?)",
                a,"a_"+a.toString().substring(0,8),"Synthetic A");
        admin.update("INSERT INTO tenant(id,code,display_name) VALUES (?,?,?)",
                b,"b_"+b.toString().substring(0,8),"Synthetic B");
        admin.update("INSERT INTO facility(id,tenant_id,code,display_name,facility_kind) VALUES (?,?,?,?,?)",
                fa,a,"clinic","Clinic A","CLINIC");
        admin.update("INSERT INTO facility(id,tenant_id,code,display_name,facility_kind) VALUES (?,?,?,?,?)",
                fb,b,"clinic","Clinic B","CLINIC");
        admin.update("INSERT INTO branch(id,tenant_id,facility_id,code,display_name) VALUES (?,?,?,?,?)",
                ba,a,fa,"main","A Main");
        admin.update("INSERT INTO branch(id,tenant_id,facility_id,code,display_name) VALUES (?,?,?,?,?)",
                bb,b,fb,"main","B Main");
        admin.update("INSERT INTO department(id,tenant_id,branch_id,code,display_name) VALUES (?,?,?,?,?)",
                da,a,ba,"opd","A OPD");
        admin.update("INSERT INTO department(id,tenant_id,branch_id,code,display_name) VALUES (?,?,?,?,?)",
                db,b,bb,"opd","B OPD");
        return new Fixture(a,b,fa,fb,ba,bb,da,db);
    }

    private Connection runtimeConnection() throws SQLException {
        return DriverManager.getConnection(postgres.getJdbcUrl(),
                "aronexa_app_runtime",RUNTIME_PASSWORD);
    }

    private static int count(Connection connection, String sql) throws SQLException {
        try (Statement s=connection.createStatement();
             ResultSet r=s.executeQuery(sql)) {
            r.next();
            return r.getInt(1);
        }
    }

    private static void setLocalTenant(Connection connection, UUID tenantId) throws SQLException {
        try (PreparedStatement s=connection.prepareStatement(
                "SELECT set_config('aronexa.tenant_id', ?, true)")) {
            s.setString(1,tenantId.toString());
            s.executeQuery().close();
        }
    }

    @Test
    void runtimeRoleIsNotOwnerSuperuserOrBypassRls() throws Exception {
        assertThat(admin.queryForObject("""
                SELECT rolsuper OR rolbypassrls OR rolcreaterole
                  FROM pg_roles WHERE rolname='aronexa_app_runtime'
                """,Boolean.class)).isFalse();
        assertThat(admin.queryForObject("""
                SELECT rolcanlogin FROM pg_roles WHERE rolname='aronexa_app_runtime'
                """,Boolean.class)).isTrue();
        assertThat(admin.queryForObject("""
                SELECT count(*) FROM pg_class c
                JOIN pg_roles r ON r.oid=c.relowner
                WHERE c.relname IN ('tenant','facility','branch','department')
                  AND r.rolname='aronexa_app_runtime'
                """,Integer.class)).isZero();
        assertThat(admin.queryForObject("""
                SELECT count(*) FROM pg_class
                WHERE relname IN ('tenant','facility','branch','department')
                  AND relrowsecurity
                """,Integer.class)).isEqualTo(4);
        assertThat(admin.queryForObject("""
                SELECT count(*) FROM pg_class
                WHERE relname IN ('facility','branch','department')
                  AND relforcerowsecurity
                """,Integer.class)).isEqualTo(3);
    }

    @Test
    void noContextHidesRowsAndRejectsTenantOwnedWrites() throws Exception {
        Fixture f=seed();
        try (Connection c=runtimeConnection()) {
            c.setAutoCommit(false);
            for (String table : new String[]{"tenant","facility","branch","department"}) {
                assertThat(count(c,"SELECT count(*) FROM "+table)).isZero();
            }
            try (PreparedStatement p=c.prepareStatement("""
                    INSERT INTO facility(id,tenant_id,code,display_name,facility_kind)
                    VALUES(?,?, 'unscoped', 'No context', 'CLINIC')
                    """)) {
                p.setObject(1,UUID.randomUUID());
                p.setObject(2,f.tenantA());
                assertThatThrownBy(p::executeUpdate).isInstanceOf(SQLException.class);
            }
            c.rollback();
        }
    }

    @Test
    void tenantAOnlySeesAAndCannotModifyOrInsertForB() throws Exception {
        Fixture f=seed();
        try (Connection c=runtimeConnection()) {
            c.setAutoCommit(false);
            setLocalTenant(c,f.tenantA());
            for (String table : new String[]{"tenant","facility","branch","department"}) {
                assertThat(count(c,"SELECT count(*) FROM "+table)).isEqualTo(1);
            }
            try (PreparedStatement p=c.prepareStatement(
                    "UPDATE facility SET display_name='compromised' WHERE id=?")) {
                p.setObject(1,f.facilityB());
                assertThat(p.executeUpdate()).isZero();
            }
            try (PreparedStatement p=c.prepareStatement(
                    "DELETE FROM department WHERE id=?")) {
                p.setObject(1,f.departmentB());
                assertThat(p.executeUpdate()).isZero();
            }
            try (PreparedStatement p=c.prepareStatement("""
                    INSERT INTO facility(id,tenant_id,code,display_name,facility_kind)
                    VALUES(?,?, 'invalidscope', 'B facility', 'CLINIC')
                    """)) {
                p.setObject(1,UUID.randomUUID());
                p.setObject(2,f.tenantB());
                assertThatThrownBy(p::executeUpdate).isInstanceOf(SQLException.class);
            }
            c.rollback();
        }
    }

    @Test
    void invalidTenantSettingFailsClosed() throws Exception {
        seed();
        try (Connection c=runtimeConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement p=c.prepareStatement(
                    "SELECT set_config('aronexa.tenant_id', ?, true)")) {
                p.setString(1,"not-a-valid-uuid");
                p.executeQuery().close();
            }
            assertThatThrownBy(() -> count(c,"SELECT count(*) FROM facility"))
                    .isInstanceOf(SQLException.class);
            c.rollback();
        }
    }

    @Test
    void tenantContextDoesNotSurviveCommitOnPooledConnection() throws Exception {
        Fixture f=seed();
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(postgres.getJdbcUrl());
        cfg.setUsername("aronexa_app_runtime");
        cfg.setPassword(RUNTIME_PASSWORD);
        cfg.setMaximumPoolSize(1);
        cfg.setMinimumIdle(0);
        cfg.setPoolName("isolated-test-pool");
        try (HikariDataSource pool=new HikariDataSource(cfg)) {
            try (Connection c=pool.getConnection()) {
                c.setAutoCommit(false);
                setLocalTenant(c,f.tenantA());
                assertThat(count(c,"SELECT count(*) FROM facility")).isEqualTo(1);
                c.commit();
            }
            try (Connection c=pool.getConnection()) {
                c.setAutoCommit(false);
                assertThat(count(c,"SELECT count(*) FROM facility")).isZero();
                setLocalTenant(c,f.tenantB());
                assertThat(count(c,"SELECT count(*) FROM facility")).isEqualTo(1);
                c.rollback();
            }
            try (Connection c=pool.getConnection()) {
                c.setAutoCommit(false);
                assertThat(count(c,"SELECT count(*) FROM facility")).isZero();
                c.rollback();
            }
        }
    }

    @Test
    void restrictedRuntimeRoleCanAuthenticateAndRevokeSessions() throws Exception {
        Fixture f=seed();
        UUID userId=UUID.randomUUID(), membershipId=UUID.randomUUID();
        String username="testlogin_"+userId.toString().substring(0,8);
        String code=admin.queryForObject("SELECT code FROM tenant WHERE id=?",
                String.class, f.tenantA());
        admin.update("INSERT INTO app_user(id,username,password_hash) VALUES (?,?,?)",
                userId,username,passwordEncoder.encode("SyntheticPassword_123!"));
        admin.update("INSERT INTO user_tenant_membership(id,user_id,tenant_id) VALUES(?,?,?)",
                membershipId,userId,f.tenantA());
        HikariConfig cfg=new HikariConfig();
        cfg.setJdbcUrl(postgres.getJdbcUrl());
        cfg.setUsername("aronexa_app_runtime");
        cfg.setPassword(RUNTIME_PASSWORD);
        cfg.setMaximumPoolSize(1);
        try (HikariDataSource runtime=new HikariDataSource(cfg)) {
            AuthService service=new AuthService(new AuthRepository(new JdbcTemplate(runtime)),
                    passwordEncoder);
            AuthService.LoginResult result=service.login(code,username,"SyntheticPassword_123!");
            assertThat(service.authenticate(result.accessToken()).tenantId()).isEqualTo(f.tenantA());
            AuthPrincipal principal=service.authenticate(result.accessToken());
            service.logout(principal,result.accessToken());
            assertThatThrownBy(() -> service.authenticate(result.accessToken()))
                    .isInstanceOf(AuthenticationRejected.class);
        }
    }

    @Test
    void trustedBinderRequiresTransactionAndDoesNotLeakOnReuse() throws Exception {
        Fixture f=seed();
        HikariConfig cfg=new HikariConfig();
        cfg.setJdbcUrl(postgres.getJdbcUrl());
        cfg.setUsername("aronexa_app_runtime");
        cfg.setPassword(RUNTIME_PASSWORD);
        cfg.setMaximumPoolSize(1);
        cfg.setMinimumIdle(0);
        try (HikariDataSource runtime=new HikariDataSource(cfg)) {
            JdbcTemplate jdbc=new JdbcTemplate(runtime);
            TenantSqlScope binder=new TenantSqlScope(new TenantAuthorization(),jdbc);
            AuthPrincipal principal=new AuthPrincipal(UUID.randomUUID(),f.tenantA(),
                    UUID.randomUUID(),"synthetic",false,List.of("RECEPTION"),List.of("ORG_READ"));
            try {
                SecurityContextHolder.getContext().setAuthentication(
                        new UsernamePasswordAuthenticationToken(principal,null,List.of()));
                assertThatThrownBy(binder::bindVerifiedTenant)
                        .isInstanceOf(IllegalStateException.class);
                TransactionTemplate transaction=new TransactionTemplate(
                        new DataSourceTransactionManager(runtime));
                transaction.executeWithoutResult(status -> {
                    binder.bindVerifiedTenant();
                    assertThat(jdbc.queryForObject("SELECT count(*) FROM facility",Integer.class))
                            .isEqualTo(1);
                });
                transaction.executeWithoutResult(status ->
                        assertThat(jdbc.queryForObject("SELECT count(*) FROM facility",Integer.class))
                                .isZero());
            } finally {
                SecurityContextHolder.clearContext();
            }
        }
    }

    @Test
    void loginTenantCodeLookupWorksWithoutOpeningTenantTable() throws Exception {
        Fixture f=seed();
        String code=admin.queryForObject("SELECT code FROM tenant WHERE id=?",String.class,f.tenantA());
        try (Connection c=runtimeConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement p=c.prepareStatement(
                    "SELECT public.aronexa_login_tenant_id(?)")) {
                p.setString(1,code);
                try(ResultSet rs=p.executeQuery()) {
                    rs.next();
                    assertThat(rs.getObject(1,UUID.class)).isEqualTo(f.tenantA());
                }
            }
            assertThat(count(c,"SELECT count(*) FROM tenant")).isZero();
            c.rollback();
        }
    }
}
