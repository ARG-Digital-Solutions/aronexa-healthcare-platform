package com.argdigital.aronexa.organization;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Internal organization persistence boundary. Not exposed as an HTTP API.
 * Caller authorization, tenant context and PostgreSQL RLS are scheduled in
 * P01-T04/P01-T05: do not use as an unguarded externally callable service.
 */
@Repository
class OrganizationDirectory {
    private final JdbcTemplate jdbc;

    OrganizationDirectory(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void createTenant(Tenant tenant) {
        jdbc.update("INSERT INTO tenant (id, code, display_name) VALUES (?, ?, ?)",
                tenant.id(), tenant.code(), tenant.displayName());
    }

    void createFacility(Facility facility) {
        jdbc.update("""
                INSERT INTO facility (id, tenant_id, code, display_name, facility_kind)
                VALUES (?, ?, ?, ?, ?)
                """, facility.id(), facility.tenantId(), facility.code(), facility.displayName(),
                facility.facilityKind());
    }

    void createBranch(Branch branch) {
        jdbc.update("""
                INSERT INTO branch (id, tenant_id, facility_id, code, display_name)
                VALUES (?, ?, ?, ?, ?)
                """, branch.id(), branch.tenantId(), branch.facilityId(), branch.code(), branch.displayName());
    }

    void createDepartment(Department department) {
        jdbc.update("""
                INSERT INTO department (id, tenant_id, branch_id, code, display_name)
                VALUES (?, ?, ?, ?, ?)
                """, department.id(), department.tenantId(), department.branchId(),
                department.code(), department.displayName());
    }

    List<Facility> facilitiesInTenant(UUID tenantId) {
        return jdbc.query("""
                SELECT id, tenant_id, code, display_name, facility_kind
                FROM facility WHERE tenant_id = ? ORDER BY code
                """, (rs, rowNum) -> new Facility(rs.getObject("id", UUID.class),
                        rs.getObject("tenant_id", UUID.class), rs.getString("code"),
                        rs.getString("display_name"), rs.getString("facility_kind")), tenantId);
    }

    List<Branch> branchesInFacility(UUID tenantId, UUID facilityId) {
        return jdbc.query("""
                SELECT id, tenant_id, facility_id, code, display_name
                FROM branch WHERE tenant_id = ? AND facility_id = ? ORDER BY code
                """, (rs, rowNum) -> new Branch(rs.getObject("id", UUID.class),
                        rs.getObject("tenant_id", UUID.class),
                        rs.getObject("facility_id", UUID.class), rs.getString("code"),
                        rs.getString("display_name")), tenantId, facilityId);
    }

    List<Department> departmentsInBranch(UUID tenantId, UUID branchId) {
        return jdbc.query("""
                SELECT id, tenant_id, branch_id, code, display_name
                FROM department WHERE tenant_id = ? AND branch_id = ? ORDER BY code
                """, (rs, rowNum) -> new Department(rs.getObject("id", UUID.class),
                        rs.getObject("tenant_id", UUID.class), rs.getObject("branch_id", UUID.class),
                        rs.getString("code"), rs.getString("display_name")), tenantId, branchId);
    }
}
