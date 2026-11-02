package com.argdigital.aronexa.auth;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Restricted-role denial and adversarial concurrent tenant context verification. */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class AuthRoleBoundaryTests {
    @Container @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine")
            .withInitScript("db/test/role-bootstrap.sql")
            .withDatabaseName("aronexa_auth_boundary_test")
            .withUsername("aronexa_auth_boundary_test")
            .withPassword("ephemeral-admin-test-only");

    @Autowired JdbcTemplate admin;

    private Connection role(String name, String password) throws SQLException {
        return DriverManager.getConnection(postgres.getJdbcUrl(),name,password);
    }

    private static int count(Connection c,String table) throws SQLException {
        try (Statement s=c.createStatement();
             ResultSet r=s.executeQuery("SELECT count(*) FROM " + table)) {
            r.next(); return r.getInt(1);
        }
    }

    @Test
    void organizationRoleCannotReadOrChangeAuthenticationTables() throws Exception {
        try(Connection c=role("aronexa_app_runtime","only-for-ephemeral-test-container")) {
            for(String table : List.of("app_user","auth_session","user_tenant_membership",
                    "auth_role","auth_membership_role","auth_role_permission","auth_audit_event")) {
                assertThatThrownBy(() -> count(c,table))
                        .as("No SELECT on " + table)
                        .isInstanceOf(SQLException.class);
            }
            try(Statement s=c.createStatement()) {
                assertThatThrownBy(() -> s.executeUpdate(
                        "UPDATE app_user SET enabled=false WHERE false"))
                        .isInstanceOf(SQLException.class);
            }
        }
    }

    @Test
    void authRoleCannotReadOrAlterOrganizationOrScopeTables() throws Exception {
        try(Connection c=role("aronexa_auth_runtime","only-for-ephemeral-auth-role")) {
            for(String table : List.of("tenant","facility","branch","department",
                    "org_membership_branch_scope","org_membership_department_scope")) {
                assertThatThrownBy(() -> count(c,table)).isInstanceOf(SQLException.class);
            }
            try(Statement s=c.createStatement()) {
                assertThatThrownBy(() -> s.executeUpdate(
                        "UPDATE facility SET display_name='forbidden' WHERE false"))
                        .isInstanceOf(SQLException.class);
            }
        }
    }

    @Test
    void neitherRoleCanEscalateToSchemaOwnerOrBypassRls() {
        assertThat(admin.queryForObject("""
            SELECT count(*) FROM pg_roles WHERE rolname IN
                ('aronexa_app_runtime','aronexa_auth_runtime')
              AND NOT rolsuper AND NOT rolbypassrls AND NOT rolcreaterole
            """, Integer.class)).isEqualTo(2);
        assertThat(admin.queryForObject("""
            SELECT count(*) FROM pg_class c JOIN pg_roles r ON c.relowner=r.oid
            WHERE r.rolname IN ('aronexa_app_runtime','aronexa_auth_runtime')
              AND c.relname IN ('app_user','tenant','facility','branch','department')
            """,Integer.class)).isZero();
    }

    @Test
    void concurrentQueriesCannotReadAnotherTenantsRowsOrRetainContext() throws Exception {
        UUID tenantA=UUID.randomUUID(), tenantB=UUID.randomUUID();
        UUID facilityA=UUID.randomUUID(),facilityB=UUID.randomUUID();
        admin.update("INSERT INTO tenant(id,code,display_name) VALUES (?,?,?)",
                tenantA,"a_"+tenantA.toString().substring(0,8),"Test A");
        admin.update("INSERT INTO tenant(id,code,display_name) VALUES (?,?,?)",
                tenantB,"b_"+tenantB.toString().substring(0,8),"Test B");
        admin.update("INSERT INTO facility(id,tenant_id,code,display_name,facility_kind) VALUES(?,?,?,?,?)",
                facilityA,tenantA,"clinic","A","CLINIC");
        admin.update("INSERT INTO facility(id,tenant_id,code,display_name,facility_kind) VALUES(?,?,?,?,?)",
                facilityB,tenantB,"clinic","B","CLINIC");

        HikariConfig cfg=new HikariConfig();
        cfg.setJdbcUrl(postgres.getJdbcUrl());
        cfg.setUsername("aronexa_app_runtime");
        cfg.setPassword("only-for-ephemeral-test-container");
        cfg.setMaximumPoolSize(2);
        cfg.setMinimumIdle(0);
        try(HikariDataSource pool=new HikariDataSource(cfg);
            ExecutorService workers=Executors.newFixedThreadPool(2)) {
            CountDownLatch gate=new CountDownLatch(1);
            var futures=new ArrayList<java.util.concurrent.Future<Boolean>>();
            for(UUID tenant : List.of(tenantA,tenantB)) {
                futures.add(workers.submit(() -> {
                    if(!gate.await(10,TimeUnit.SECONDS)) return false;
                    for(int i=0;i<5;i++) {
                        try(Connection c=pool.getConnection()) {
                            c.setAutoCommit(false);
                            // No context prior to setting this transaction's tenant.
                            if(count(c,"facility")!=0) return false;
                            try(PreparedStatement ps=c.prepareStatement(
                                    "SELECT set_config('aronexa.tenant_id', ?, true)")) {
                                ps.setString(1,tenant.toString());
                                ps.executeQuery().close();
                            }
                            try(PreparedStatement ps=c.prepareStatement(
                                    "SELECT tenant_id FROM facility")) {
                                try(ResultSet rs=ps.executeQuery()) {
                                    if(!rs.next() || !tenant.equals(rs.getObject(1,UUID.class))
                                            || rs.next()) return false;
                                }
                            }
                            c.rollback();
                        }
                    }
                    return true;
                }));
            }
            gate.countDown();
            for(var future : futures) assertThat(future.get(30,TimeUnit.SECONDS)).isTrue();
            try(Connection c=pool.getConnection()) {
                c.setAutoCommit(false);
                assertThat(count(c,"facility")).isZero();
                c.rollback();
            }
        }
    }
}
