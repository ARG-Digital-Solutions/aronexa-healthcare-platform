package com.argdigital.aronexa.opd;

import com.argdigital.aronexa.auth.TenantAuthorization;
import com.argdigital.aronexa.auth.TenantSqlScope;
import com.argdigital.aronexa.moduleregistry.ModuleAccessService;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * RC02A review-gated staff directory.
 *
 * This intentionally uses distinct read-only runtime credentials:
 * appJdbc checks authenticated tenant's live assigned branch via RLS,
 * authJdbc reads only active doctor staff membership/username (NO passwords).
 * It never joins patient identities or returns clinical demographics.
 *
 * NOTE: Cross-datasource admin-revocation atomicity remains a separate
 * security-review gate (#123/#125). Directory activation is FALSE by default;
 * never enable in a customer deployment without reviewed SQL lock protocol.
 */
@Service
public final class OpdDoctorDirectoryService {
    private final TenantAuthorization auth;
    private final ModuleAccessService modules;
    private final TenantSqlScope scope;
    private final JdbcTemplate appJdbc;
    private final JdbcTemplate authJdbc;

    public OpdDoctorDirectoryService(TenantAuthorization auth,
            ModuleAccessService modules, TenantSqlScope scope,
            JdbcTemplate appJdbc,
            @Qualifier("authJdbcTemplate") JdbcTemplate authJdbc) {
        this.auth=auth;
        this.modules=modules;
        this.scope=scope;
        this.appJdbc=appJdbc;
        this.authJdbc=authJdbc;
    }

    public record DoctorOption(UUID membershipId,String label) {}

    @Transactional(readOnly=true)
    public List<DoctorOption> list(UUID branchId) {
        var actor=auth.requirePermission("APPOINTMENT_BOOK");
        if(branchId==null||branchId.equals(new UUID(0,0)))
            throw new AccessDeniedException("Doctor directory denied");
        modules.requireEnabled("OPD");
        scope.bindVerifiedTenant();

        // App runtime has RLS scoped to the trusted tenant, NOT authentication
        // tables. Check staff branch assignment immediately before listing.
        var assigned=appJdbc.queryForList("""
            SELECT 1 FROM public.org_membership_branch_scope
             WHERE tenant_id=? AND branch_id=? AND membership_id=?
            """,Integer.class,actor.tenantId(),branchId,actor.membershipId());
        if(assigned.isEmpty())throw new AccessDeniedException("Doctor directory denied");

        var inBranch=appJdbc.queryForList("""
            SELECT membership_id FROM public.org_membership_branch_scope
             WHERE tenant_id=? AND branch_id=?
             ORDER BY membership_id LIMIT 101
            """,UUID.class,actor.tenantId(),branchId);
        if(inBranch.size()>100)throw new AccessDeniedException("Directory too large");
        Set<UUID> allowed=new HashSet<>(inBranch);
        // Auth runtime has SELECT on identity/role tables, no access to
        // org branch data or patients. Filter against the authorized branch
        // membership set before returning any staff identifiers.
        var candidates=authJdbc.query("""
            SELECT DISTINCT m.id AS membership_id,u.username
              FROM public.user_tenant_membership m
              JOIN public.app_user u ON u.id=m.user_id
              JOIN public.auth_membership_role mr
                ON mr.membership_id=m.id AND mr.tenant_id=m.tenant_id
              JOIN public.auth_role r
                ON r.id=mr.role_id AND r.tenant_id=mr.tenant_id
             WHERE m.tenant_id=? AND m.enabled AND u.enabled
               AND r.code='DOCTOR'
             ORDER BY u.username,m.id
            """,(rs,i)->new DoctorOption(
                rs.getObject("membership_id",UUID.class),
                rs.getString("username")),actor.tenantId());
        var selected=candidates.stream()
                .filter(x->allowed.contains(x.membershipId())).toList();
        if(selected.size()>25)
            throw new AccessDeniedException("Directory too large");
        return List.copyOf(selected);
    }
}

@RestController
@ConditionalOnProperty(name="aronexa.opd.directory.reviewed-activation",
        havingValue="true",matchIfMissing=false)
@RequestMapping("/api/v1/branches/{branchId}/opd/doctors")
final class OpdDoctorDirectoryController {
    private final OpdDoctorDirectoryService directory;
    OpdDoctorDirectoryController(OpdDoctorDirectoryService directory) {
        this.directory=directory;
    }
    @GetMapping
    ResponseEntity<List<OpdDoctorDirectoryService.DoctorOption>> list(
            @PathVariable UUID branchId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(directory.list(branchId));
    }
}
