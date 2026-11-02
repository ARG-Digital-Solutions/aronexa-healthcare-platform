package com.argdigital.aronexa.auth;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Unit contract for P01-T05-A. Does not claim PostgreSQL RLS coverage. */
class TenantAuthorizationTests {
    private final TenantAuthorization authorization = new TenantAuthorization();
    private final UUID tenantOne = UUID.randomUUID();
    private final UUID tenantTwo = UUID.randomUUID();

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private AuthPrincipal establishSession(boolean mustChangePassword, List<String> roles,
                                           List<String> permissions) {
        AuthPrincipal principal = new AuthPrincipal(UUID.randomUUID(), tenantOne, UUID.randomUUID(),
                "doctor_example", mustChangePassword, roles, permissions);
        var authorities = permissions.stream().map(SimpleGrantedAuthority::new).toList();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, authorities));
        return principal;
    }

    @Test
    void deniesMissingAuthentication() {
        assertThatThrownBy(authorization::currentPrincipal)
                .isInstanceOf(AuthenticationCredentialsNotFoundException.class);
        assertThatThrownBy(() -> authorization.requireTenant(tenantOne))
                .isInstanceOf(AuthenticationCredentialsNotFoundException.class);
    }

    @Test
    void deniesAnonymousOrUntrustedPrincipalType() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("anonymousUser", null, List.of()));
        assertThatThrownBy(authorization::currentPrincipal)
                .isInstanceOf(AuthenticationCredentialsNotFoundException.class);
    }

    @Test
    void deniesUnauthenticatedPrincipal() {
        AuthPrincipal principal = establishSession(false, List.of("DOCTOR"), List.of("ORG_READ"));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, "unverified"));
        assertThatThrownBy(authorization::currentPrincipal)
                .isInstanceOf(AuthenticationCredentialsNotFoundException.class);
    }

    @Test
    void permitsOnlyMatchingTenantScope() {
        AuthPrincipal principal = establishSession(false, List.of("DOCTOR"), List.of("ORG_READ"));
        assertThat(authorization.requireTenant(tenantOne)).isSameAs(principal);
        assertThatThrownBy(() -> authorization.requireTenant(tenantTwo))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> authorization.requireTenant(null))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void exactPermissionAndTenantMustBothMatch() {
        AuthPrincipal principal = establishSession(false, List.of("DOCTOR"), List.of("ORG_READ"));
        assertThat(authorization.requireTenantPermission(tenantOne, "ORG_READ")).isSameAs(principal);
        assertThatThrownBy(() -> authorization.requireTenantPermission(tenantTwo, "ORG_READ"))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> authorization.requireTenantPermission(tenantOne, "ORG_WRITE"))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> authorization.requireTenantPermission(tenantOne, null))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> authorization.requirePermission("org_read"))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void adminRoleDoesNotImplicitlyGrantClinicalAccess() {
        establishSession(false, List.of("PLATFORM_ADMIN"), List.of("ORG_READ"));
        assertThatThrownBy(() -> authorization.requirePermission("CLINICAL_READ"))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void firstLoginPasswordChangeBlocksResourcePermissions() {
        establishSession(true, List.of("RECEPTION"), List.of("ORG_READ"));
        assertThatThrownBy(() -> authorization.requirePermission("ORG_READ"))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> authorization.requireTenantPermission(tenantOne, "ORG_READ"))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void doesNotLeakAuthenticationAcrossClearedContexts() {
        establishSession(false, List.of("RECEPTION"), List.of("ORG_READ"));
        assertThat(authorization.requireTenant(tenantOne).tenantId()).isEqualTo(tenantOne);
        SecurityContextHolder.clearContext();
        assertThatThrownBy(authorization::currentPrincipal)
                .isInstanceOf(AuthenticationCredentialsNotFoundException.class);
    }
}
