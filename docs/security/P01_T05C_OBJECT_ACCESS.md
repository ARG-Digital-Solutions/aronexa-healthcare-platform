# P01-T05-C — Organization object authorization foundation

**Status:** internal foundation. This task adds real facility, branch, and department object policies, but **does not finish doctor/patient/encounter authorization** because those domain entities do not exist yet. No patient API or production access is permitted.

## Existing source-of-truth model
A tenant owns facilities; facilities own branches; branches own departments (V2).
A verified account is bound to a tenant membership (V3), with `ORG_READ` granted through tenant-role permissions.
Organization rows are tenant-RLS-protected under a non-owner runtime DB role (V4).
No existing approved `doctor_profile`, `patient`, `encounter`, or doctor-to-branch/department assignment schema is present. Do **not** create fictitious clinician records or claim clinical object authorization based solely on tenant membership.

## V5 assignment contract
- `org_membership_branch_scope` binds a verified tenant membership to an explicitly assigned branch.
- `org_membership_department_scope` binds the same tenant membership to a department **only when the branch is also assigned**.
- Composite foreign keys reject cross-tenant assignment, missing branch membership, and department in a different branch.
- Both assignment tables are tenant-RLS-protected, with runtime role **SELECT only**; administrative assignment provisioning is deferred and has no public API.
- A membership with `ORG_READ` but **no branch assignment** sees no branch/department. A tenant admin or platform admin does not bypass membership/object policies.
- Facility access is derived from at least one assigned branch within the facility (not a free-standing facility privilege).

## Application service contract
`OrganizationAccessService` uses `TenantAuthorization.requirePermission("ORG_READ")` and `TenantSqlScope.bindVerifiedTenant()` in the same database transaction. It resolves ownership from persisted resource and membership joins rather than using caller-provided tenant headers or trusting a requested resource owner. `facility(id)`, `branch(id)`, `department(id)`, `branches()`, `departments(branchId)` are internal service operations **only**, with no HTTP exposure.

Unknown and unauthorized object IDs both yield `Optional.empty()` / empty lists. Missing authentication or permission returns denial. No broad role-name bypass exists. Exact authorization must be reviewed again before introducing any new HTTP route.

## Verified/required tests
Real PostgreSQL Testcontainers with an actual `aronexa_app_runtime` non-owner role and transaction-scoped context:
- Assigned branch/department and facility allowed; list results scoped.
- Same-tenant but unassigned branch/department denied (IDOR).
- Other-tenant object IDs denied.
- Another membership in the **same tenant** cannot borrow an assignment.
- `PLATFORM_ADMIN` without `ORG_READ` denied; forced password change/anonymous denied.
- No transaction denied; SQL tenant context clears after transaction.
- Database FK rejects cross-tenant, wrong-branch and missing membership department assignments.
- Runtime role cannot modify assignment mappings; unscoped table read returns zero.

No customer data and no clinical tables are used. The isolated test's migration/database-owner connection is only used for synthetic fixture setup; authorization service reads through the restricted role.

## Explicit uncompleted security gates
1. **Doctor/clinician assignments**, department clinical access policies, patient and encounter ownership must follow their future approved domain schemas. There is no clinician data model to authorize today. All clinical and unknown HTTP routes remain deny-by-default.
2. **Authentication tables are not yet RLS protected** (known P01-T05-B high-priority issue). A compromised runtime SQL session could access cross-tenant auth data.
3. **P01-T05-D** will provide concurrency, hostile input, connection reuse, invalid role/membership, and full endpoint/resource object security review. This task's tests are targeted, not a complete security certification.
4. Production administrative staff provisioning and audited assignment lifecycle, emergency access, multi-branch clinical policy, data restoration, real patient/doctor records, and release sign-off are not implemented.

## Migration/recovery
V5 is additive; V1–V4 must remain unmodified. Never delete the developer volume or execute Flyway clean to make migrations pass. Existing migrations were not deployed to customer databases. Owner authorization is required before merging or beginning P01-T05-D.
