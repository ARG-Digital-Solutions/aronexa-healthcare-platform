# P01-T05-C — Organization scoped resource authorization

**Status:** READY FOR OWNER REVIEW (organization-object portion only); not merged or deployed.
**Branch:** `feature/p01-t05c-organization-object-access`
**Draft PR:** https://github.com/ARG-Digital-Solutions/aronexa-healthcare-platform/pull/16
**Evidence:** [GitHub Actions run 41426321970](https://github.com/ARG-Digital-Solutions/aronexa-healthcare-platform/actions/runs/41426321970), initially verified at commit `0086c51`.
**Classification:** INTERNAL FOUNDATION; not production- or clinical-ready.

## Approved scope and changes
- Additive Flyway **V5** creates tenant/membership branch+department scope mappings with composite FKs, read-only runtime grants and tenant RLS. Existing V1–V4 untouched.
- `OrganizationAccessService` checks the session's `ORG_READ` permission, opens transaction-bound verified tenant context, and resolves actual persisted facility/branch/department ownership plus explicit membership assignments.
- Facility readable only if the caller is assigned to at least one branch of that facility; departments additionally require explicit matching department assignment. Unknown and forbidden object IDs are indistinguishable to callers.
- Internal methods `facility(id)`, `branch(id)`, `department(id)`, `branches()`, `departments(branchId)`. **No new HTTP endpoint or clinical data model.**
- No doctor/patient/encounter schema exists yet, so their clinical object authorization remains **NOT IMPLEMENTED**. Unregistered HTTP routes remain denied.

## Executed verification
| Command / check | Evidence |
|---|---|
| GitHub Actions Documentation validation | PASS |
| PR scope and dangerous-file checks | PASS |
| Tracked secret patterns | PASS, limited regex scan |
| Docker Compose syntax and Docker runtime preflight | PASS |
| `mvn -B -f backend/pom.xml verify` | **PASS: 49 Java tests, 0 failures/errors/skips** |
| `OrganizationAccessIntegrationTests` (restricted PostgreSQL role) | **PASS: 7 tests** |
| Existing PostgreSQL RLS tests | PASS: 8 |
| Authentication HTTP tests | PASS: 12 |
| TenantAuthorization unit tests | PASS: 8 |
| OrganizationPersistenceTests | PASS: 9 |
| Application context/Flyway tests | PASS: 4 |
| Spring Modulith architecture checks | PASS: 1 |
| Flyway V1–V5 migration | PASS: schema version 5 |
| `npm ci --no-audit --no-fund && npm run build` | PASS: TypeScript/Vite |

## Key negative assertions
- Same-tenant but unassigned branch/department object ID denied; restricted lists exclude such objects.
- Cross-tenant object IDs denied, even with `ORG_READ` in the active tenant.
- A different tenant membership cannot borrow the same tenant's branch assignment.
- Platform admin role alone grants no organization object access.
- Forced-password-change, anonymous/no auth context or missing permission denied.
- Missing database transaction denied; transaction-local RLS context clears on next pooled use.
- DB rejects cross-tenant assignment, department assigned to wrong branch, and unassigned branch.
- Runtime role is read-only on scope assignment tables; missing tenant setting returns no rows.

## Unresolved security gates and limitations
1. **Doctor, patient, encounter and clinical object policies** are not implemented because their domain/ownership models do not yet exist. This PR completes only the currently possible organization object scope of T05-C, not the full clinical-object requirement.
2. **Authentication-table privileged SQL gap remains** from P01-T05-B: runtime auth schema SQL permissions are not tenant-RLS-isolated. This is a high-priority security blocker before any real patient data or production usage.
3. P01-T05-D adversarial concurrent HTTP/SQL testing and security reviewer approval not performed.
4. Staff onboarding and audited branch assignment provisioning have no API yet; synthetic database fixtures were used.
5. No local developer data volume migration performed; only isolated ephemeral PostgreSQL Testcontainers were used. No real patient/clinical data, deployment or customer migration.
6. Organization read-only service policy does not authorize future mutation endpoints or privileged staff doctor/patient access.
7. GitHub private organization ruleset isn't technically enforced under current plan; owner must manually review the PR diff and latest CI.

## V3 phase report
Task P01-T05-C organization foundation, no UI changes, no clinical release. Additive V5 only; no destructive rollback or Flyway clean. Tests pass for supported entity types; full doctor/patient access and end-to-end security certification remain open. **Owner approval required before merge and before next work item.**
