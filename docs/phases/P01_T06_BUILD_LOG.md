# P01-T06 — Module Registry and Entitlement Enforcement

**Status:** IMPLEMENTED FOR REVIEW; **TESTS NOT YET VERIFIED** until CI and local test evidence are collected.  
**Authorization:** Explicit owner instruction dated 2026-11-03 (IST), bounded to P01-T06.  
**Branch:** `feature/p01-t06-module-entitlement-gates`. **Do not merge/deploy or proceed to P01-T07 without separate approval.**  
**Classification:** INTERNAL_FOUNDATION candidate only; no patient use, clinical use, subscription billing or customer release.

## Traceability and limits
- Master Engineering Contract V3.0: central registry modules must be implemented, released, entitled and activated; clinical authorization is a separate gate; disabled modules deny access and historical records survive.
- AGENTS.md rule 15. Phase task card P01-T06: minimal registry, entitlements and activation gates.
- Shared module codes: OPD, IPD, PHARMACY, BILLING, LAB, IUI and IVF. Adding specialities later requires a separately reviewed change.
- No API to provision entitlements or releases, no UI, no patient/encounter/order domain tables, no subscription payments, no enabled modules by default.
- No change to authentication API, existing Flyway V1–V6, or login/tenant boundary.

## Changed files
1. `backend/src/main/resources/db/migration/V7__module_registry_and_tenant_entitlements.sql` — catalog, per-tenant entitlement/activation/clinical authorization state, append-only configuration-change audit, constrained release prerequisite and PostgreSQL RLS.
2. `backend/src/main/java/com/argdigital/aronexa/moduleregistry/ModuleAccessService.java` — transaction-scoped, server-verified tenant gate for future module APIs.
3. `backend/src/test/java/com/argdigital/aronexa/moduleregistry/ModuleAccessServiceTests.java` — unit negative paths.
4. `backend/src/test/java/com/argdigital/aronexa/moduleregistry/ModuleRegistryPostgresTests.java` — real PostgreSQL restricted-role and RLS tests.
5. This build log.

## Security design and threat review
- All module codes are initialized `implemented=false` and `released=false`. No tenant entitlement rows are seeded and no clinical module can start enabled by migration.
- Global release requires `implemented=true` (DB check). Runtime gate requires **all** of: valid authenticated principal, password change not mandatory, verified transaction tenant context, implemented, released, entitled, activated, and clinical approval when applicable.
- No user-supplied tenant, module entitlement or role can become trusted. Unknown module code, missing enrollment, expired session context or disabled gate fail closed.
- Module access is a **necessary precondition**, not a replacement for a future endpoint's role/permission/branch/record authorization. Module features must invoke this gate *and* their separate object policy; future APIs are outside this PR.
- Tenant configuration protected by PostgreSQL RLS. Organization runtime SELECT-only; auth runtime has no module table privileges; both runtime principals have no entitlement mutation or delete permissions.
- Migration-owner-only setup (no public grant API). Changes to registry or tenant state write an audit trail via database triggers. Audit rows cannot be updated/deleted via ordinary SQL; privileges for the owner/DBA remain a distinct operational trust boundary.
- Disabling/revoking a module changes flags; it does **not** drop module tables, remove historical state or cascade-delete patient data. Historical clinical models are not yet present.

## Proposed verification commands / evidence
From repository root:
```powershell
mvn -B -f .\backend\pom.xml clean verify
cd .\frontend
npm ci
npm audit
npm run build
cd ..
git diff --check
```
Real PostgreSQL tests require Docker Desktop or Linux Docker daemon; test DB uses ephemeral synthetic Testcontainers and pre-provisioned non-owner SQL identities.

| Required check | Status |
|---|---|
| Compile and full backend test suite incl new P01-T06 tests | NOT VERIFIED pending CI |
| Flyway clean V1–V7 ephemeral database | NOT VERIFIED pending CI |
| Cross-tenant and missing tenant context denial | NOT VERIFIED pending CI |
| All gates and fail-closed unit tests | NOT VERIFIED pending CI |
| Runtime/auth SQL privilege denial | NOT VERIFIED pending CI |
| Audit immutability and history preservation | NOT VERIFIED pending CI |
| Frontend npm audit and build regression | NOT VERIFIED pending CI |
| Windows local existing-volume V6→V7 | NOT RUN — migration requires separate safe backup/preflight; do not use a customer DB |
| Static secret/scope checks and PR jobs | NOT VERIFIED pending CI |

## Open blockers and acceptance
- P01 foundation still has the earlier global auth-role SQL exposure and incomplete MFA/recovery/abuse throttling; P01-T06 does not remediate them.
- No clinical object models, E2E module APIs, real user provisioning, delegated entitlements, commercial billing or Windows offline installer exist. Future module endpoints cannot be treated as enabled until they implement both module gate and RBAC/object policies.
- Clinical release authorization and tenant entitlements require future audited, separately approved administrative provisioning. Migration owner controls are not a self-service UI.
- Review migration safety and backup/restore before modifying any persistent local developer database. Do not run Flyway clean or reset volumes.
- This report records code authoring, **not a verified passing test or customer acceptance**. Owner must inspect PR diff, latest green CI and residual risks and decide whether to approve future merge explicitly.

**Recommendation:** READY FOR CODE REVIEW ONLY, subject to real executed verification; do not merge until appropriate tests pass and owner separately approves merge.

## CI corrective work — 2026-11-03

The initial PR #21 CI runs failed because **historical checks expected Flyway V6** despite the new forward-only V7. Evidence from [Application CI run 41504420170](https://github.com/ARG-Digital-Solutions/aronexa-healthcare-platform/actions/runs/41504420170): 64 tests executed, 63 passed / 1 failed; the sole failure was `AronexaApplicationTests.freshDatabaseIsMigratedAndMigrationIsValidated` (expected 6, actual 7). The **11 new P01-T06 tests passed**, and fresh Testcontainers database reached V7. [Fresh dev run 41504420163](https://github.com/ARG-Digital-Solutions/aronexa-healthcare-platform/actions/runs/41504420163) also reached a healthy PostgreSQL and started Spring Boot, then failed its obsolete 'wanted 6' schema assertion. Neither failure demonstrates a V7 migration failure.

Corrective commits update the existing application migration expectation to V7 (plus verify registry begins locked down) and the fresh CI script's V7 check. Existing V1–V6 files are unchanged. **Do not mark new runs PASS until their actual workflow results are observed.**
