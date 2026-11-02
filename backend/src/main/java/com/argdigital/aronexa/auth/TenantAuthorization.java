package com.argdigital.aronexa.auth;

import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

/**
 * P01-T05-A: application-level tenant authorization primitive.
 *
 * Derives the tenant only from the AuthPrincipal placed in Spring SecurityContext
 * by the validated, database-backed bearer filter. It NEVER reads tenant IDs
 * from headers, request bodies, URL parameters or a custom ThreadLocal.
 *
 * NOTE: Not PostgreSQL RLS or full branch/object authorization. Those require
 * separately approved P01-T05-B/C/D gates.
 */
@Service
public class TenantAuthorization {

    /** Return the authenticated session identity. No anonymous/string principals. */
    public AuthPrincipal currentPrincipal() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof AuthPrincipal principal)
                || principal.userId() == null || principal.tenantId() == null
                || principal.membershipId() == null) {
            throw new AuthenticationCredentialsNotFoundException(
                    "An authenticated tenant membership is required");
        }
        return principal;
    }

    /** Ensure a requested resource's tenant matches the verified session tenant. */
    public AuthPrincipal requireTenant(UUID resourceTenantId) {
        AuthPrincipal principal = currentPrincipal();
        if (resourceTenantId == null || !principal.tenantId().equals(resourceTenantId)) {
            throw new AccessDeniedException("Tenant scope denied");
        }
        return principal;
    }

    /**
     * Require an exact permission granted to the active tenant membership.
     * A role name (including PLATFORM_ADMIN) confers no implicit override.
     */
    public AuthPrincipal requirePermission(String permission) {
        AuthPrincipal principal = currentPrincipal();
        if (principal.mustChangePassword() || permission == null || permission.isBlank()
                || principal.permissions() == null || !principal.permissions().contains(permission)) {
            throw new AccessDeniedException("Permission denied");
        }
        return principal;
    }

    /** Recommended precondition for all future tenant-owned resource services. */
    public AuthPrincipal requireTenantPermission(UUID resourceTenantId, String permission) {
        AuthPrincipal principal = requireTenant(resourceTenantId);
        if (principal.mustChangePassword() || permission == null || permission.isBlank()
                || principal.permissions() == null || !principal.permissions().contains(permission)) {
            throw new AccessDeniedException("Permission denied");
        }
        return principal;
    }
}
