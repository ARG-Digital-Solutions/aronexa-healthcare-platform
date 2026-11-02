# P01-T05 — Four approval-gated authorization increments

Owner approval in conversation on 2026-11-02 authorizes beginning P01-T05. To preserve small independently testable Codex tasks, implement and seek approval of each increment before advancing. This document is a plan, not evidence of completed security controls.

| Task | Goal | Non-negotiable acceptance |
|---|---|---|
| **P01-T05-A (this draft PR)** | Verified identity-derived tenant authorization helpers and HTTP deny-by-default for unregistered routes | No anonymous tenant, no arbitrary tenant header/body selection, exact tenant + permission required, password-change gate, no blanket admin override; Spring security HTTP tests and existing regression pass |
| P01-T05-B | DB least-privileged runtime/migration roles, RLS policies and transaction-scoped tenant context | Real non-BYPASSRLS/non-owner app role; no tenant context denies; `SET LOCAL` inside transaction; cross-tenant read/write disallowed, pooled connection residue and malformed settings rejected |
| P01-T05-C | Branch/department/doctor and per-resource (patient/encounter etc. as applicable) access service policies | Resolve object ownership server-side, enforce branch/role/doctor assignments, protect IDOR and list endpoints; no implicit global PHI access for platform admins |
| P01-T05-D | Adversarial integration and concurrency validation | Concurrent tenants, connection pool reuse, forged IDs/headers, revoked sessions, multiple memberships, direct SQL, audit tests and explicit security reviewer sign-off |

## A architecture
Verified API token -> AuthService -> tenant-bound AuthPrincipal -> Spring SecurityContext -> TenantAuthorization (tenant and exact permission checks). Future business application services must call `requireTenantPermission(resourceTenant, permission)` **after trusted resource ownership lookup**, not infer a resource tenant from caller-supplied IDs. This is an application-level gate only; no RLS, client-provided tenant switching, or clinical APIs.

Default HTTP deny-all except the four existing auth endpoints prevents accidentally exposed future controllers from being reachable just because a session is valid. New HTTP routes require independent explicit matcher decisions and matching service authorization before merge.

## Security limitations
- Permissions are loaded from the authorized database membership by the bearer authentication filter for each HTTP request.
- `TenantAuthorization` trusts only `AuthPrincipal` from authenticated Spring SecurityContext; direct tests can construct mock principals solely within trusted test code.
- No custom request ThreadLocal, `X-Tenant-Id`, query-tenant binding or header-based tenant override.
- Current `AuthRepository` and `OrganizationDirectory` can still be called by privileged internal code without DB RLS. **No tenant isolation guarantee for direct SQL** until P01-T05-B.
- Branch and clinical object policies are not implemented; no real patient information should be processed or exposed.
- There is no authorization for platform-admin PHI access: role labels are not permission bypasses.
- Future security reviewer must cover account provisioning, effective session revocation, rate limits, audit, PHI release and RLS prior to production.

## Merge rule
Every task remains a draft PR until its tests and acceptance review pass. Never merge, deploy or start the next increment without explicit owner instruction.
