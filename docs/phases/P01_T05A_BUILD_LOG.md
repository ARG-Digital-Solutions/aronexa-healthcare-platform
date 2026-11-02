# P01-T05-A — Verified tenant authorization context

**Status:** IN PROGRESS — initial code and tests; latest CI evidence not yet recorded.
**Branch:** `feature/p01-t05a-tenant-policy`
**Draft PR:** Pending creation
**Classification:** INTERNAL_FOUNDATION / NOT READY for clinical or customer release

## Scope and changed behavior
- `TenantAuthorization` checks the Spring-authenticated `AuthPrincipal` created from verified, unexpired, enabled tenant membership, never a caller-selected tenant header.
- Denies missing or anonymous security context, mismatched tenant IDs, null tenant, missing/incorrect permissions, and clinical access inferred from an admin role.
- Current-user endpoint is backed by the same trusted authorization context, without altering its successful JSON contract.
- Spring HTTP configuration permits exactly login, me, logout and password change; all unregistered routes are denied, including those accessed by authenticated sessions.
- Synthetic tests cover these decisions; no new database migration, patient APIs, RLS or deployment.

## Verification required
| Check | Status |
|---|---|
| `mvn -B -f backend/pom.xml verify` and Spring Modulith | NOT RUN |
| PostgreSQL/Testcontainers authentication regression | NOT RUN |
| TenantAuthorization unit tests | NOT RUN |
| HTTP spoofed tenant header / unregistered route denial tests | NOT RUN |
| `npm ci --no-audit --no-fund && npm run build` | NOT RUN |
| Documentation, scope and basic secret checks | NOT RUN |
| Tenant-aware PostgreSQL RLS / pooled-connection isolation | NOT IMPLEMENTED — P01-T05-B |
| Branch/doctor/patient IDOR enforcement | NOT IMPLEMENTED — P01-T05-C/D |

## Risks and sign-off
HTTP controllers for clinical data must not be exposed without a separately approved permission policy, trustworthy resource ownership and DB isolation. Existing package-private repositories do not themselves prevent privileged SQL from reading other tenants. GitHub ruleset is saved but cannot be technically enforced for the private organization repo on current plan, so review CI and PR manually. No merge, data migration or deployment authorized.
