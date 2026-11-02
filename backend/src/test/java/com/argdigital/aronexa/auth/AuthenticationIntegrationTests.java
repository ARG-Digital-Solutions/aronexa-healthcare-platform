package com.argdigital.aronexa.auth;

import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real PostgreSQL and real HTTP security filter tests with generated synthetic identities. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class AuthenticationIntegrationTests {
    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine")
            .withDatabaseName("aronexa_auth_test")
            .withUsername("aronexa_auth_test")
            .withPassword("ephemeral-test-only");

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;

    record Fixture(UUID userId, UUID tenantId, UUID memberId, String tenantCode,
                   String username, String password) {}

    private Fixture fixture() {
        String suffix = UUID.randomUUID().toString().substring(0,8);
        UUID tenantId=UUID.randomUUID(),userId=UUID.randomUUID(), memberId=UUID.randomUUID();
        String code="t_"+suffix, username="user_"+suffix, password="TempPassword_123!";
        jdbc.update("INSERT INTO tenant(id,code,display_name) VALUES (?,?,?)",
                tenantId,code,"Test facility owner");
        jdbc.update("INSERT INTO app_user(id,username,password_hash) VALUES (?,?,?)",
                userId,username,passwords.encode(password));
        jdbc.update("INSERT INTO user_tenant_membership(id,user_id,tenant_id) VALUES (?,?,?)",
                memberId,userId,tenantId);
        UUID roleId=UUID.randomUUID();
        jdbc.update("INSERT INTO auth_role(id,tenant_id,code,display_name) VALUES (?,?,?,?)",
                roleId,tenantId,"RECEPTION","Reception");
        jdbc.update("""
            INSERT INTO auth_permission(code,description) VALUES (?,?)
            ON CONFLICT(code) DO NOTHING
            ""","ORG_READ","Organization read (future gated API)");
        jdbc.update("INSERT INTO auth_role_permission(tenant_id,role_id,permission_code) VALUES (?,?,?)",
                tenantId,roleId,"ORG_READ");
        jdbc.update("INSERT INTO auth_membership_role(tenant_id,membership_id,role_id) VALUES (?,?,?)",
                tenantId,memberId,roleId);
        return new Fixture(userId,tenantId,memberId,code,username,password);
    }

    private String loginJson(Fixture f, String password) {
        return String.format("{\"tenantCode\":\"%s\",\"username\":\"%s\",\"password\":\"%s\"}",
                f.tenantCode(),f.username(),password);
    }
    private String login(Fixture f,String password) throws Exception {
        String json=mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson(f,password)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.mustChangePassword").value(true))
                .andReturn().getResponse().getContentAsString();
        Matcher match=Pattern.compile("accessToken[^A-Za-z0-9_-]*([A-Za-z0-9_-]{43})").matcher(json);
        assertThat(match.find()).isTrue();
        return match.group(1);
    }

    @Test
    void loginReturnsOnlyOpaqueTokenAndMeHasTenantScopedRoles() throws Exception {
        Fixture f=fixture();
        String token=login(f,f.password());
        assertThat(token).hasSize(43);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auth_session WHERE token_sha256=?",
                Integer.class,token)).isZero();
        mvc.perform(get("/api/v1/auth/me").header("Authorization","Bearer "+token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value(f.tenantId().toString()))
                .andExpect(jsonPath("$.roles[0]").value("RECEPTION"))
                .andExpect(jsonPath("$.permissions[0]").value("ORG_READ"))
                .andExpect(jsonPath("$.mustChangePassword").value(true));
    }

    @Test
    void unauthenticatedAndMalformedTokensAreRejected() throws Exception {
        mvc.perform(get("/api/v1/auth/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/auth/me").header("Authorization","Bearer not-a-token"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/auth/me").header("Authorization","Basic bad"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void ignoresForgedTenantAndBranchHeadersForSessionIdentity() throws Exception {
        Fixture f=fixture();
        String token=login(f,f.password());
        mvc.perform(get("/api/v1/auth/me")
                .header("Authorization","Bearer "+token)
                .header("X-Tenant-Id",UUID.randomUUID().toString())
                .header("X-Branch-Id",UUID.randomUUID().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value(f.tenantId().toString()))
                .andExpect(jsonPath("$.membershipId").value(f.memberId().toString()));
    }

    @Test
    void unregisteredApplicationRouteIsDeniedAfterPasswordChange() throws Exception {
        Fixture f=fixture();
        String token=login(f,f.password());
        mvc.perform(post("/api/v1/auth/password")
                .header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON)
                .content("{\\"currentPassword\\":\\"TempPassword_123!\\",\\"newPassword\\":\\"NewLongPassword_123!\\"}"))
                .andExpect(status().isNoContent());
        String newToken=loginWithoutFirstPasswordRequirement(f,"NewLongPassword_123!");
        mvc.perform(get("/api/v1/clinical/future")
                .header("Authorization","Bearer "+newToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void logoutRevokesAccessAndWritesAudit() throws Exception {
        Fixture f=fixture(); String token=login(f,f.password());
        mvc.perform(post("/api/v1/auth/logout").header("Authorization","Bearer "+token))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/auth/me").header("Authorization","Bearer "+token))
                .andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM auth_audit_event WHERE user_id=? AND event_code='LOGOUT'",
                Integer.class,f.userId())).isEqualTo(1);
    }

    @Test
    void passwordChangeRequiresCurrentPasswordAndRevokesAllSessions() throws Exception {
        Fixture f=fixture(); String token=login(f,f.password());
        mvc.perform(post("/api/v1/auth/password")
                .header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"wrong\",\"newPassword\":\"NewLongPassword_123!\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/auth/me").header("Authorization","Bearer "+token))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/auth/password")
                .header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"TempPassword_123!\",\"newPassword\":\"NewLongPassword_123!\"}"))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/auth/me").header("Authorization","Bearer "+token))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(loginJson(f,f.password()))).andExpect(status().isUnauthorized());
        String newToken=loginWithoutFirstPasswordRequirement(f,"NewLongPassword_123!");
        mvc.perform(get("/api/v1/auth/me").header("Authorization","Bearer "+newToken))
                .andExpect(jsonPath("$.mustChangePassword").value(false));
    }
    private String loginWithoutFirstPasswordRequirement(Fixture f,String password) throws Exception {
        String json=mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(loginJson(f,password))).andExpect(status().isOk())
                .andExpect(jsonPath("$.mustChangePassword").value(false))
                .andReturn().getResponse().getContentAsString();
        Matcher match=Pattern.compile("accessToken[^A-Za-z0-9_-]*([A-Za-z0-9_-]{43})").matcher(json);
        assertThat(match.find()).isTrue();
        return match.group(1);
    }

    @Test
    void locksAfterFiveWrongPasswordsAndDoesNotResetOnCorrectCredentials() throws Exception {
        Fixture f=fixture();
        for (int i=0; i<5; i++) {
            mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                    .content(loginJson(f,"WrongPassword_123!")))
                    .andExpect(status().isUnauthorized());
        }
        assertThat(jdbc.queryForObject("SELECT failed_logins FROM app_user WHERE id=?",
                Integer.class,f.userId())).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT locked_until IS NOT NULL FROM app_user WHERE id=?",
                Boolean.class,f.userId())).isTrue();
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(loginJson(f,f.password()))).andExpect(status().isUnauthorized());
    }

    @Test
    void disabledMembershipAndUnknownTenantAreDenied() throws Exception {
        Fixture f=fixture();
        jdbc.update("UPDATE user_tenant_membership SET enabled=FALSE WHERE id=?",f.memberId());
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(loginJson(f,f.password()))).andExpect(status().isUnauthorized());
        jdbc.update("UPDATE user_tenant_membership SET enabled=TRUE WHERE id=?",f.memberId());
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"tenantCode\":\"missing\",\"username\":\""+f.username()+
                        "\",\"password\":\""+f.password()+"\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void crossTenantRoleCannotBeAssignedToMembership() {
        Fixture a=fixture(), b=fixture();
        UUID roleId=jdbc.queryForObject(
                "SELECT id FROM auth_role WHERE tenant_id=? AND code='RECEPTION'",
                UUID.class,b.tenantId());
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO auth_membership_role(tenant_id,membership_id,role_id) VALUES (?,?,?)",
                a.tenantId(), a.memberId(), roleId)).isInstanceOf(
                        org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void expiredSessionCannotAccessIdentity() throws Exception {
        Fixture f=fixture();
        String token=login(f,f.password());
        jdbc.update("""
            UPDATE auth_session SET issued_at=now()-interval '2 hours',
                   expires_at=now()-interval '1 hour' WHERE membership_id=?
            """,f.memberId());
        mvc.perform(get("/api/v1/auth/me").header("Authorization","Bearer "+token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void firstLoginCannotAccessOrdinaryApplicationRoutes() throws Exception {
        Fixture f=fixture();
        String token=login(f,f.password());
        mvc.perform(get("/api/v1/clinical/future").header("Authorization","Bearer "+token))
                .andExpect(status().isForbidden());
    }

    @Test
    void tokenSessionIsInvalidAfterMembershipDisabled() throws Exception {
        Fixture f=fixture(); String token=login(f,f.password());
        jdbc.update("UPDATE user_tenant_membership SET enabled=FALSE WHERE id=?",f.memberId());
        mvc.perform(get("/api/v1/auth/me").header("Authorization","Bearer "+token))
                .andExpect(status().isUnauthorized());
    }
}
