package com.argdigital.aronexa.organization;

import com.argdigital.aronexa.auth.AuthPrincipal;
import com.argdigital.aronexa.auth.TenantAuthorization;
import com.argdigital.aronexa.auth.TenantSqlScope;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Internal, read-only, tenant+membership-scoped organization object policy.
 * Do NOT expose as a public controller without explicit HTTP security review.
 *
 * Never authorize from caller-supplied tenant IDs: database-owned resource
 * relationships and migration-managed membership assignments are authoritative.
 * Unknown and forbidden objects return indistinguishable Optional.empty().
 */
@Service
public class OrganizationAccessService {
    private final TenantAuthorization authorization;
    private final TenantSqlScope sqlScope;
    private final JdbcTemplate jdbc;

    public OrganizationAccessService(TenantAuthorization authorization,
                                     TenantSqlScope sqlScope, JdbcTemplate jdbc) {
        this.authorization = authorization;
        this.sqlScope = sqlScope;
        this.jdbc = jdbc;
    }

    private AuthPrincipal readScope() {
        AuthPrincipal principal = authorization.requirePermission("ORG_READ");
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Scoped organization reads require a transaction");
        }
        sqlScope.bindVerifiedTenant();
        return principal;
    }

    @Transactional(readOnly = true)
    public Optional<Facility> facility(UUID facilityId) {
        AuthPrincipal p = readScope();
        if (facilityId == null) return Optional.empty();
        return jdbc.query("""
            SELECT f.id, f.tenant_id, f.code, f.display_name, f.facility_kind
              FROM facility f
             WHERE f.id = ? AND f.tenant_id = ?
               AND EXISTS (
                   SELECT 1 FROM org_membership_branch_scope s
                   JOIN branch b ON b.tenant_id = s.tenant_id AND b.id = s.branch_id
                   WHERE s.tenant_id = f.tenant_id AND s.membership_id = ?
                     AND b.facility_id = f.id
               )
            """, (rs, i) -> new Facility(
                rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
                rs.getString("code"), rs.getString("display_name"),
                rs.getString("facility_kind")), facilityId, p.tenantId(), p.membershipId())
                .stream().findFirst();
    }

    @Transactional(readOnly = true)
    public Optional<Branch> branch(UUID branchId) {
        AuthPrincipal p = readScope();
        if (branchId == null) return Optional.empty();
        return jdbc.query("""
            SELECT b.id, b.tenant_id, b.facility_id, b.code, b.display_name
              FROM branch b
              JOIN org_membership_branch_scope s
                ON s.tenant_id = b.tenant_id AND s.branch_id = b.id
             WHERE b.id = ? AND b.tenant_id = ? AND s.membership_id = ?
            """, (rs, i) -> branch(rs), branchId, p.tenantId(), p.membershipId())
                .stream().findFirst();
    }

    @Transactional(readOnly = true)
    public Optional<Department> department(UUID departmentId) {
        AuthPrincipal p = readScope();
        if (departmentId == null) return Optional.empty();
        return jdbc.query("""
            SELECT d.id, d.tenant_id, d.branch_id, d.code, d.display_name
              FROM department d
              JOIN org_membership_department_scope s
                ON s.tenant_id = d.tenant_id AND s.branch_id = d.branch_id
               AND s.department_id = d.id
             WHERE d.id = ? AND d.tenant_id = ? AND s.membership_id = ?
            """, (rs, i) -> department(rs), departmentId, p.tenantId(), p.membershipId())
                .stream().findFirst();
    }

    @Transactional(readOnly = true)
    public List<Branch> branches() {
        AuthPrincipal p = readScope();
        return jdbc.query("""
            SELECT b.id, b.tenant_id, b.facility_id, b.code, b.display_name
              FROM branch b
              JOIN org_membership_branch_scope s
                ON s.tenant_id = b.tenant_id AND s.branch_id = b.id
             WHERE b.tenant_id = ? AND s.membership_id = ?
             ORDER BY b.code, b.id
            """, (rs, i) -> branch(rs), p.tenantId(), p.membershipId());
    }

    @Transactional(readOnly = true)
    public List<Department> departments(UUID branchId) {
        AuthPrincipal p = readScope();
        if (branchId == null) return List.of();
        return jdbc.query("""
            SELECT d.id, d.tenant_id, d.branch_id, d.code, d.display_name
              FROM department d
              JOIN org_membership_department_scope s
                ON s.tenant_id = d.tenant_id AND s.branch_id = d.branch_id
               AND s.department_id = d.id
             WHERE d.tenant_id = ? AND d.branch_id = ? AND s.membership_id = ?
             ORDER BY d.code, d.id
            """, (rs, i) -> department(rs), p.tenantId(), branchId, p.membershipId());
    }

    private Branch branch(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Branch(rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("facility_id", UUID.class),
                rs.getString("code"), rs.getString("display_name"));
    }

    private Department department(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Department(rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("branch_id", UUID.class),
                rs.getString("code"), rs.getString("display_name"));
    }
}
