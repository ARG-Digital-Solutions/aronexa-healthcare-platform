# P01-T03 — Tenant / Facility / Branch / Department foundation

**Scope:** Initial organization persistence model, Flyway V2 migration and PostgreSQL integrity tests. No HTTP API, authentication, end-user tenant switching, doctor/clinical records, or tenant RLS.

## Relationship model
```text
tenant [id, code, display_name]
  └── facility [id, tenant_id, code, display_name, facility_kind]
        └── branch [id, tenant_id, facility_id, code, display_name]
              └── department [id, tenant_id, branch_id, code, display_name]
```

- Tenant is the owning organization boundary; one tenant can have multiple facilities.
- Facility represents a configured institution (clinic, hospital, day-care etc.). `facility_kind` is descriptive, **not** a module entitlement or independent clinical platform. Future types may be added without changing existing patient identity.
- Branch is a location/site under one facility; department is a functional unit in one branch. Department and branch labels are tenant-local, not clinical specialties or entitlement rules.
- Every child carries a `tenant_id` identifying its intended owner. Internal persistence exposes no reassignment methods, but database-level prohibitions on updating ownership and authenticated access enforcement are **not yet implemented**; these require dedicated follow-up controls before production. Composite foreign keys `(tenant_id, facility_id)` and `(tenant_id, branch_id)` enforce that children cannot reference parents belonging to a different tenant. Uniqueness is scoped by owner; invalid codes and blank names are rejected.
- Hard deletion of a parent with children is refused by foreign keys. Explicit lifecycle/deactivation and audit are future tasks, rather than silent cascade deletion.
- All SQL migrations are forward-only. **Do not change applied V1 or V2**; use a new Flyway migration for corrections.

## Java module
`com.argdigital.aronexa.organization` contains four records and an internal JDBC repository (`OrganizationDirectory`). The repository is deliberately package-private and provides tenant-scoped read queries; it is **not exposed through HTTP APIs**.

**Important security limitation:** Tenant IDs passed by a trusted internal caller are not a substitute for identity authorization. The P01-T03 schema prevents invalid cross-tenant parent/child links but does **not** prevent direct database updates of ownership when references remain valid, or enforce per-user visibility, row-level security or branch/doctor access. Those belong to separately approved P01-T04/P01-T05. Do not expose this directory to user input before enforcing tenant context and authorization.

## Verification / rollback plan
Run `mvn -B -f backend/pom.xml verify` with Docker enabled. Testcontainers starts isolated PostgreSQL 17 and Flyway migrates from V1 to V2. Tests assert hierarchy persistence, tenant-scoped lookups, cross-tenant rejection, codes uniqueness and deletion protection. Existing Spring Modulith and frontend tests must remain passing.
Do **not** use `flyway clean` or destructive rollback on a populated database. If migration fails, keep the PR unmerged, inspect logs and create a **new** corrective migration where necessary. Restore from an approved backup if a customer deployment is ever affected; none is authorized for P01-T03.

## Out of scope
Tenant onboarding APIs, identity/RBAC, schema or table RLS, special database roles, audit events, patient/doctor/appointment features, SaaS entitlements, UI and live deployment.
