package com.argdigital.aronexa.auth;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SEC-T13 (#100): actual embedded servlet HTTP + disposable PG17, not MockMvc.
 *
 * Real error dispatch must not turn an authenticated first-login 403 into a
 * misleading 401. No patient clinical route is registered or enabled here.
 * Synthetic tenant/user data only, never the original Windows database.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.address=127.0.0.1")
@ActiveProfiles("test")
@Testcontainers
class RealHttpDenialStatusTests {
    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine")
            .withInitScript("db/test/role-bootstrap.sql")
            .withDatabaseName("aronexa_real_http_denial_test")
            .withUsername("aronexa_real_http_denial_test")
            .withPassword("ephemeral-test-only");

    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;
    @Value("${local.server.port}") int port;

    private final HttpClient client = HttpClient.newHttpClient();

    record FictionalUser(UUID tenantId, String tenantCode, String username,
                         String password) {}

    private FictionalUser fictionalUser() {
        UUID tenant = UUID.randomUUID(), user = UUID.randomUUID(),
                membership = UUID.randomUUID(), role = UUID.randomUUID();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String tenantCode = "http_" + suffix;
        String username = "fictional_" + suffix;
        String password = "SyntheticPassword_123!";
        jdbc.update("INSERT INTO tenant(id,code,display_name) VALUES (?,?,?)",
                tenant, tenantCode, "Fictional local-only clinic");
        jdbc.update("INSERT INTO app_user(id,username,password_hash) VALUES (?,?,?)",
                user, username, passwords.encode(password));
        jdbc.update("INSERT INTO user_tenant_membership(id,user_id,tenant_id) VALUES (?,?,?)",
                membership, user, tenant);
        jdbc.update("INSERT INTO auth_role(id,tenant_id,code,display_name) VALUES (?,?,?,?)",
                role, tenant, "RECEPTION", "Synthetic reception");
        jdbc.update("""
                INSERT INTO auth_permission(code,description) VALUES ('ORG_READ','Synthetic test')
                ON CONFLICT(code) DO NOTHING
                """);
        jdbc.update("INSERT INTO auth_role_permission(tenant_id,role_id,permission_code) VALUES (?,?,?)",
                tenant, role, "ORG_READ");
        jdbc.update("INSERT INTO auth_membership_role(tenant_id,membership_id,role_id) VALUES (?,?,?)",
                tenant, membership, role);
        return new FictionalUser(tenant, tenantCode, username, password);
    }

    private HttpResponse<String> get(String path, String bearer) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .GET();
        if (bearer != null) request.header("Authorization", "Bearer " + bearer);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String body, String bearer) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (bearer != null) request.header("Authorization", "Bearer " + bearer);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String login(FictionalUser user, String password) throws Exception {
        String body = String.format(
                "{\"tenantCode\":\"%s\",\"username\":\"%s\",\"password\":\"%s\"}",
                user.tenantCode(), user.username(), password);
        var response = post("/api/v1/auth/login", body, null);
        assertThat(response.statusCode()).isEqualTo(200);
        Matcher token = Pattern.compile("\"accessToken\"\\s*:\\s*\"([A-Za-z0-9_-]{43})\"")
                .matcher(response.body());
        assertThat(token.find()).isTrue();
        return token.group(1);
    }

    @Test
    void firstLoginRequiredPasswordChangeReturns403Not401OnRealTomcat() throws Exception {
        FictionalUser user = fictionalUser();
        String token = login(user, user.password());
        assertThat(get("/api/v1/auth/me", token).statusCode()).isEqualTo(200);
        var denied = get("/api/v1/clinical/future", token);
        assertThat(denied.statusCode()).isEqualTo(403);
        assertThat(denied.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(denied.body()).doesNotContain("SyntheticPassword", "Fictional local-only");
    }

    @Test
    void regularAuthenticatedSessionRemainsDeniedByDefaultSecurityPolicy() throws Exception {
        FictionalUser user = fictionalUser();
        String old = login(user, user.password());
        var changed = post("/api/v1/auth/password",
                "{\"currentPassword\":\"SyntheticPassword_123!\"," +
                "\"newPassword\":\"SyntheticNewPassword_456!\"}", old);
        assertThat(changed.statusCode()).isEqualTo(204);
        assertThat(get("/api/v1/auth/me", old).statusCode()).isEqualTo(401);
        String fresh = login(user, "SyntheticNewPassword_456!");
        assertThat(get("/api/v1/auth/me", fresh).statusCode()).isEqualTo(200);
        assertThat(get("/api/v1/clinical/future", fresh).statusCode()).isEqualTo(403);
    }

    @Test
    void missingAndMalformedBearerTokensStillReturn401() throws Exception {
        assertThat(get("/api/v1/auth/me", null).statusCode()).isEqualTo(401);
        assertThat(get("/api/v1/auth/me", "invalid-token").statusCode()).isEqualTo(401);
    }
}
