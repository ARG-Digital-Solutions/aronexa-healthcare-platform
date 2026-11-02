# P01-T05-C — Organization scoped resource authorization

**Status:** IN PROGRESS — CI evidence pending.
**Branch:** `feature/p01-t05c-organization-object-access`
**Classification:** INTERNAL FOUNDATION; not production- or clinical-ready.

## Changes
- Flyway V5 adds composite-FK tenant/membership branch+department scope tables, read-only runtime grants, and tenant RLS.
- Internal organization access service performs exact permission check and transaction-local tenant setting, resolves persisted facility/branch/department scope and filters lists.
- No HTTP endpoints, clinician/patient schemas or live clinical functionality.
- New restricted-role Testcontainers tests for same-tenant and cross-tenant IDOR attempts, unassigned permissions, role bypass, client context fail-closed and ownership integrity.

## Evidence to collect
| Check | Status |
|---|---|
| `mvn -B -f backend/pom.xml verify` + Spring Modulith | NOT RUN |
| Flyway V1–V5 fresh migration and validation | NOT RUN |
| Restricted runtime-role organization object policy tests | NOT RUN |
| Existing authentication/RLS and PostgreSQL regressions | NOT RUN |
| `npm ci --no-audit --no-fund && npm run build` | NOT RUN |
| GitHub Actions documentation, PR scope and secret-pattern scans | NOT RUN |
| Clinical doctor/patient/encounter authorization | NOT IMPLEMENTED — future domain-dependent work |
| Auth-table privileged SQL isolation | NOT IMPLEMENTED — security blocker |
| P01-T05-D adversarial/concurrent full tests | NOT RUN |

## Gates
Draft PR only. User must review diff and latest passing CI, and approve merge. No deployment, irreversible migrations to customer data, production accounts, or move to T05-D without explicit approval.
