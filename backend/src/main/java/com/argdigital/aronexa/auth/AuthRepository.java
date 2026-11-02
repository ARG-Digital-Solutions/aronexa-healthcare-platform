package com.argdigital.aronexa.auth;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Package-private persistence boundary. Only authentication endpoints are exposed. */
@Repository
class AuthRepository {
    record LoginAccount(UUID userId, UUID membershipId, UUID tenantId, String username,
                        String passwordHash, boolean enabled, boolean memberEnabled,
                        boolean mustChangePassword, int failedLogins, Instant lockedUntil) {}

    private final JdbcTemplate jdbc;
    AuthRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    Optional<LoginAccount> lookupForLogin(String tenantCode, String username) {
        return jdbc.query("""
            SELECT u.id AS user_id, m.id AS membership_id, m.tenant_id, u.username,
                   u.password_hash, u.enabled, m.enabled AS member_enabled,
                   u.must_change_password, u.failed_logins, u.locked_until
              FROM app_user u
              JOIN user_tenant_membership m ON m.user_id = u.id
              JOIN tenant t ON t.id = m.tenant_id
             WHERE t.code = ? AND u.username = ?
             FOR UPDATE OF u
            """, (rs, i) -> new LoginAccount(
                    rs.getObject("user_id", UUID.class),
                    rs.getObject("membership_id", UUID.class),
                    rs.getObject("tenant_id", UUID.class),
                    rs.getString("username"), rs.getString("password_hash"),
                    rs.getBoolean("enabled"), rs.getBoolean("member_enabled"),
                    rs.getBoolean("must_change_password"), rs.getInt("failed_logins"),
                    rs.getObject("locked_until", java.time.OffsetDateTime.class) == null ? null :
                      rs.getObject("locked_until", java.time.OffsetDateTime.class).toInstant()),
            tenantCode, username).stream().findFirst();
    }

    void failedLogin(UUID id, int attempts, Instant lockedUntil) {
        jdbc.update("UPDATE app_user SET failed_logins=?, locked_until=? WHERE id=?",
                attempts, lockedUntil == null ? null : java.sql.Timestamp.from(lockedUntil), id);
    }
    void clearFailedLogins(UUID id) {
        jdbc.update("UPDATE app_user SET failed_logins=0, locked_until=NULL WHERE id=?", id);
    }
    void storeSession(UUID membershipId, UUID tenantId, String digest, Instant expiry) {
        jdbc.update("""
            INSERT INTO auth_session(id, membership_id, tenant_id, token_sha256, expires_at)
            VALUES (?, ?, ?, ?, ?)
            """, UUID.randomUUID(), membershipId, tenantId, digest,
                java.sql.Timestamp.from(expiry));
    }

    Optional<AuthPrincipal> lookupSession(String digest, Instant now) {
        var accounts = jdbc.query("""
            SELECT u.id AS user_id, u.username, u.must_change_password,
                   m.tenant_id, m.id AS membership_id
            FROM auth_session s
            JOIN user_tenant_membership m ON m.id=s.membership_id AND m.tenant_id=s.tenant_id
            JOIN app_user u ON u.id=m.user_id
            WHERE s.token_sha256=? AND s.revoked_at IS NULL AND s.expires_at>?
              AND u.enabled AND m.enabled
            """, (rs,i) -> new Object[] {
                rs.getObject("user_id", UUID.class), rs.getObject("tenant_id", UUID.class),
                rs.getObject("membership_id", UUID.class), rs.getString("username"),
                rs.getBoolean("must_change_password")
            }, digest, java.sql.Timestamp.from(now));
        if (accounts.isEmpty()) return Optional.empty();
        Object[] a=accounts.getFirst();
        UUID tenantId=(UUID)a[1], membershipId=(UUID)a[2];
        List<String> roles=jdbc.queryForList("""
            SELECT r.code FROM auth_membership_role mr
            JOIN auth_role r ON r.tenant_id=mr.tenant_id AND r.id=mr.role_id
            WHERE mr.tenant_id=? AND mr.membership_id=? ORDER BY r.code
            """, String.class, tenantId, membershipId);
        List<String> permissions=jdbc.queryForList("""
            SELECT DISTINCT rp.permission_code FROM auth_membership_role mr
            JOIN auth_role_permission rp ON rp.tenant_id=mr.tenant_id AND rp.role_id=mr.role_id
            WHERE mr.tenant_id=? AND mr.membership_id=? ORDER BY rp.permission_code
            """, String.class, tenantId, membershipId);
        return Optional.of(new AuthPrincipal((UUID)a[0],tenantId,membershipId,(String)a[3],
                (Boolean)a[4],roles,permissions));
    }
    void revokeSession(String digest) {
        jdbc.update("UPDATE auth_session SET revoked_at=now() WHERE token_sha256=? AND revoked_at IS NULL", digest);
    }
    void revokeAllUserSessions(UUID userId) {
        jdbc.update("""
            UPDATE auth_session SET revoked_at=now() WHERE revoked_at IS NULL
            AND membership_id IN (SELECT id FROM user_tenant_membership WHERE user_id=?)
            """,userId);
    }
    Optional<String> passwordHash(UUID userId) {
        return jdbc.query("SELECT password_hash FROM app_user WHERE id=? AND enabled",
                (rs,i)->rs.getString(1),userId).stream().findFirst();
    }
    void replacePassword(UUID userId,String passwordHash) {
        jdbc.update("""
            UPDATE app_user SET password_hash=?, must_change_password=FALSE,
                password_updated_at=now(), failed_logins=0, locked_until=NULL WHERE id=?
            """,passwordHash,userId);
    }
    void audit(UUID userId, UUID tenantId, String code) {
        jdbc.update("INSERT INTO auth_audit_event(user_id, tenant_id, event_code) VALUES (?,?,?)",
                userId,tenantId,code);
    }
}
