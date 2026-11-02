package com.argdigital.aronexa.moduleregistry;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
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

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class ModuleRegistryPostgresTests {
    @Container @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine")
            .withInitScript("db/test/role-bootstrap.sql")
            .withDatabaseName("aronexa_module_test")
            .withUsername("aronexa_module_test")
            .withPassword("ephemeral-test-only");
    @Autowired JdbcTemplate admin;

    @BeforeEach
    void resetCatalogForIsolation() {
        admin.update("DELETE FROM tenant_module_state");
        admin.update("UPDATE module_registry SET implemented=false, released=false");
    }

    private UUID createTenant() {
        UUID id=UUID.randomUUID();
        admin.update("INSERT INTO tenant(id,code,display_name) VALUES (?,?,?)",
                id,"t_"+id.toString().substring(0,8),"Synthetic");
        return id;
    }

    private Connection runtime() throws SQLException {
        return DriverManager.getConnection(postgres.getJdbcUrl(),
                "aronexa_app_runtime","only-for-ephemeral-test-container");
    }

    private int accessible(Connection c,String module) throws SQLException {
        try (PreparedStatement ps=c.prepareStatement("""
            SELECT count(*) FROM tenant_module_state s
            JOIN module_registry r ON r.code=s.module_code
            WHERE s.module_code=? AND r.implemented AND r.released
              AND s.entitled AND s.activated
              AND (NOT r.requires_clinical_authorization OR s.clinically_authorized)
            """)) {
            ps.setString(1,module);
            try (ResultSet rs=ps.executeQuery()) { rs.next(); return rs.getInt(1); }
        }
    }

    private void context(Connection c,UUID tenant) throws SQLException {
        try(PreparedStatement ps=c.prepareStatement(
                "SELECT set_config('aronexa.tenant_id', ?, true)")) {
            ps.setString(1,tenant.toString());
            ps.executeQuery().close();
        }
    }

    @Test
    void defaultModulesAreUnreleasedAndNoTenantIsEntitled() {
        assertThat(admin.queryForObject(
            "SELECT count(*) FROM module_registry WHERE implemented OR released",
            Integer.class)).isZero();
        assertThat(admin.queryForObject("SELECT count(*) FROM tenant_module_state",Integer.class))
                .isZero();
    }

    @Test
    void eachGateDeniesUntilAllConditionsMetAndRetainsHistory() throws Exception {
        UUID t=createTenant();
        admin.update("INSERT INTO tenant_module_state(tenant_id,module_code,entitled,activated,clinically_authorized) VALUES (?, 'OPD', true, true, true)", t);
        try(Connection c=runtime()) {
            c.setAutoCommit(false); context(c,t);
            assertThat(accessible(c,"OPD")).isZero();
            c.rollback();
        }
        admin.update("UPDATE module_registry SET implemented=true,released=true WHERE code='OPD'");
        try(Connection c=runtime()) {
            c.setAutoCommit(false); context(c,t);
            assertThat(accessible(c,"OPD")).isEqualTo(1);
            c.rollback();
        }
        admin.update("UPDATE tenant_module_state SET activated=false WHERE tenant_id=? AND module_code='OPD'",t);
        try(Connection c=runtime()) {
            c.setAutoCommit(false); context(c,t);
            assertThat(accessible(c,"OPD")).isZero();
            c.rollback();
        }
        assertThat(admin.queryForObject(
            "SELECT count(*) FROM tenant_module_state WHERE tenant_id=? AND module_code='OPD'",
            Integer.class,t)).isEqualTo(1);
        assertThat(admin.queryForObject(
            "SELECT count(*) FROM module_configuration_audit WHERE tenant_id=? AND module_code='OPD'",
            Integer.class,t)).isGreaterThanOrEqualTo(2);
    }

    @Test
    void clinicalAuthorizationCannotBeBypassed() throws Exception {
        UUID t=createTenant();
        admin.update("UPDATE module_registry SET implemented=true,released=true WHERE code='IVF'");
        admin.update("INSERT INTO tenant_module_state(tenant_id,module_code,entitled,activated) VALUES (?,'IVF',true,true)",t);
        try(Connection c=runtime()) {
            c.setAutoCommit(false); context(c,t);
            assertThat(accessible(c,"IVF")).isZero();
            c.rollback();
        }
        admin.update("UPDATE tenant_module_state SET clinically_authorized=true WHERE tenant_id=? AND module_code='IVF'",t);
        try(Connection c=runtime()) {
            c.setAutoCommit(false); context(c,t);
            assertThat(accessible(c,"IVF")).isEqualTo(1);
            c.rollback();
        }
    }

    @Test
    void crossTenantMissingContextAndPooledReuseFailClosed() throws Exception {
        UUID a=createTenant(),b=createTenant();
        admin.update("UPDATE module_registry SET implemented=true,released=true WHERE code='BILLING'");
        admin.update("INSERT INTO tenant_module_state(tenant_id,module_code,entitled,activated) VALUES (?,'BILLING',true,true)",a);
        try(Connection c=runtime()) {
            c.setAutoCommit(false);
            assertThat(accessible(c,"BILLING")).isZero();
            context(c,b); assertThat(accessible(c,"BILLING")).isZero();
            c.commit();
            c.setAutoCommit(false);
            assertThat(accessible(c,"BILLING")).isZero();
            context(c,a); assertThat(accessible(c,"BILLING")).isEqualTo(1);
            c.rollback();
        }
    }

    @Test
    void runtimeCannotMutateRegistryEntitlementOrAuditAndAuthCannotReadThem() throws Exception {
        try(Connection c=runtime(); Statement st=c.createStatement()) {
            for(String table : new String[]{"module_registry","tenant_module_state","module_configuration_audit"}) {
                assertThatThrownBy(() -> st.executeUpdate("DELETE FROM "+table+" WHERE false"))
                        .isInstanceOf(SQLException.class);
            }
        }
        try(Connection c=DriverManager.getConnection(postgres.getJdbcUrl(),
                "aronexa_auth_runtime","only-for-ephemeral-auth-role");
            Statement st=c.createStatement()) {
            for(String table : new String[]{"module_registry","tenant_module_state","module_configuration_audit"}) {
                assertThatThrownBy(() -> st.executeQuery("SELECT count(*) FROM "+table))
                        .isInstanceOf(SQLException.class);
            }
        }
    }

    @Test
    void auditIsAppendOnlyEvenForMigrationOwnerAndReleaseRequiresImplementation() {
        assertThatThrownBy(() -> admin.update(
                "UPDATE module_registry SET released=true WHERE code='LAB'"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        Long existingId=admin.queryForObject(
                "SELECT min(id) FROM module_configuration_audit",Long.class);
        assertThat(existingId).isNotNull();
        assertThatThrownBy(() -> admin.update(
                "DELETE FROM module_configuration_audit WHERE id=?",existingId))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
}
