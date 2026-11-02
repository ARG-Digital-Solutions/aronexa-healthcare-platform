package com.argdigital.aronexa.moduleregistry;

import com.argdigital.aronexa.auth.TenantAuthorization;
import com.argdigital.aronexa.auth.TenantSqlScope;
import java.util.List;
import java.util.Locale;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * P01-T06 internal server-side module gate. Do not rely on frontend visibility
 * or client-controlled tenant/entitlement flags for authorization.
 *
 * Every future module API must call requireEnabled before accessing its data.
 * No customer-facing module API is introduced by this foundation.
 */
@Service
public class ModuleAccessService {
    private final TenantAuthorization authorization;
    private final TenantSqlScope scope;
    private final JdbcTemplate jdbc;

    public ModuleAccessService(TenantAuthorization authorization, TenantSqlScope scope,
                               JdbcTemplate jdbc) {
        this.authorization = authorization;
        this.scope = scope;
        this.jdbc = jdbc;
    }

    /** Throws on any missing gate. No role (including platform admin) bypasses this. */
    @Transactional(readOnly = true)
    public void requireEnabled(String moduleCode) {
        var principal = authorization.currentPrincipal();
        if (principal.mustChangePassword()) {
            throw new AccessDeniedException("Module access denied");
        }
        if (moduleCode == null || !moduleCode.matches("[A-Z][A-Z0-9_]{1,47}")) {
            throw new AccessDeniedException("Module access denied");
        }
        // Tenant binding must use the verified server-side principal inside this transaction.
        scope.bindVerifiedTenant();
        List<Integer> allowed = jdbc.queryForList("""
            SELECT 1
              FROM public.tenant_module_state s
              JOIN public.module_registry r ON r.code = s.module_code
             WHERE s.tenant_id = ? AND s.module_code = ?
               AND r.implemented AND r.released
               AND s.entitled AND s.activated
               AND (NOT r.requires_clinical_authorization OR s.clinically_authorized)
            """, Integer.class, principal.tenantId(), moduleCode);
        if (allowed.isEmpty()) {
            throw new AccessDeniedException("Module access denied");
        }
    }
}
