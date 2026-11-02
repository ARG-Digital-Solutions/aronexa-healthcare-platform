package com.argdigital.aronexa.organization;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Real PostgreSQL integrity tests. These do NOT test authorization or RLS:
 * those need identity and a restricted DB application role in P01-T04/T05.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@Transactional
class OrganizationPersistenceTests {
    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine")
            .withInitScript("db/test/role-bootstrap.sql")
            .withDatabaseName("aronexa_test")
            .withUsername("aronexa_test")
            .withPassword("ephemeral-test-only");

    @Autowired OrganizationDirectory directory;
    @Autowired JdbcTemplate jdbc;

    private Tenant tenant(String code) {
        Tenant tenant = new Tenant(UUID.randomUUID(), code, "Test " + code);
        directory.createTenant(tenant);
        return tenant;
    }

    private Facility facility(Tenant tenant, String code) {
        Facility facility = new Facility(UUID.randomUUID(), tenant.id(), code, "Facility " + code, "CLINIC");
        directory.createFacility(facility);
        return facility;
    }

    private Branch branch(Tenant tenant, Facility facility, String code) {
        Branch branch = new Branch(UUID.randomUUID(), tenant.id(), facility.id(), code, "Branch " + code);
        directory.createBranch(branch);
        return branch;
    }

    @Test
    void persistsHierarchyAndScopesInternalDirectoryLookups() {
        Tenant one = tenant("tenant_one");
        Tenant two = tenant("tenant_two");
        Facility clinicOne = facility(one, "clinic");
        Facility clinicTwo = facility(two, "clinic");
        Branch branchOne = branch(one, clinicOne, "main");
        Branch branchTwo = branch(two, clinicTwo, "main");
        Department department = new Department(UUID.randomUUID(), one.id(), branchOne.id(),
                "opd", "Outpatient");
        directory.createDepartment(department);

        assertThat(directory.facilitiesInTenant(one.id())).containsExactly(clinicOne);
        assertThat(directory.facilitiesInTenant(two.id())).containsExactly(clinicTwo);
        assertThat(directory.branchesInFacility(one.id(), clinicOne.id())).containsExactly(branchOne);
        assertThat(directory.branchesInFacility(two.id(), clinicTwo.id())).containsExactly(branchTwo);
        assertThat(directory.branchesInFacility(two.id(), clinicOne.id())).isEmpty();
        assertThat(directory.departmentsInBranch(one.id(), branchOne.id())).containsExactly(department);
        assertThat(directory.departmentsInBranch(two.id(), branchOne.id())).isEmpty();
    }

    @Test
    void rejectsCrossTenantFacilityAssignmentForBranch() {
        Tenant one = tenant("tenant_one");
        Tenant two = tenant("tenant_two");
        Facility target = facility(two, "clinic");

        assertThatThrownBy(() -> directory.createBranch(new Branch(
                UUID.randomUUID(), one.id(), target.id(), "illegal", "Cross tenant")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsCrossTenantBranchAssignmentForDepartment() {
        Tenant one = tenant("tenant_one");
        Tenant two = tenant("tenant_two");
        Facility facility = facility(two, "clinic");
        Branch target = branch(two, facility, "main");

        assertThatThrownBy(() -> directory.createDepartment(new Department(
                UUID.randomUUID(), one.id(), target.id(), "illegal", "Cross tenant")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsDuplicateTenantCodes() {
        Tenant one = tenant("tenant_one");
        assertThatThrownBy(() -> directory.createTenant(
                new Tenant(UUID.randomUUID(), one.code(), "Duplicate")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsBranchWithMissingFacility() {
        Tenant one = tenant("tenant_one");
        assertThatThrownBy(() -> directory.createBranch(new Branch(
                UUID.randomUUID(), one.id(), UUID.randomUUID(), "new_branch", "Orphan")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void departmentCodeIsUniqueWithinBranch() {
        Tenant one = tenant("tenant_one");
        Facility clinic = facility(one, "clinic");
        Branch main = branch(one, clinic, "main");
        directory.createDepartment(new Department(
                UUID.randomUUID(), one.id(), main.id(), "opd", "Outpatient"));

        assertThatThrownBy(() -> directory.createDepartment(new Department(
                UUID.randomUUID(), one.id(), main.id(), "opd", "Duplicate")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void branchCodeIsUniqueWithinFacility() {
        Tenant one = tenant("tenant_one");
        Facility clinic = facility(one, "clinic");
        branch(one, clinic, "main");

        assertThatThrownBy(() -> branch(one, clinic, "main"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void cannotDeleteParentWhileChildExists() {
        Tenant one = tenant("tenant_one");
        facility(one, "clinic");

        assertThatThrownBy(() -> jdbc.update("DELETE FROM tenant WHERE id = ?", one.id()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsMalformedOrganizationCodes() {
        assertThatThrownBy(() -> directory.createTenant(
                new Tenant(UUID.randomUUID(), "Spaces Not Allowed", "Bad")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
