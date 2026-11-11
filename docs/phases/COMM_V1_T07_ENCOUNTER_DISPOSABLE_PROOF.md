# COMM-V1-T07 — Encounter Draft Contract and Disposable PostgreSQL Security Proof

**Parent:** Development #84; commercial baseline #72.
**Classification:** INTERNAL_FOUNDATION / NOT_READY. No clinical or customer release.
**Master:** Engineering Contract V3 sections 3–8, 10, 17–22. Windows offline first commercial release; Online SaaS developed in parallel.
**Owner direction:** 2026-11-11 "proceed with next" for bounded synthetic/disposable implementation only.

## Existing code inspected
Current main contains AuthPrincipal, TenantAuthorization, TenantSqlScope, PatientSqlScope, module entitlement checks and V8 canonical patient identity. V9 registration read-boundary source is present; the original Windows V8 to V9 migration remains W3-E/W4 HOLD under #31. There is no production OPD encounter API on main. AuthSecurityConfiguration defaults to denying any unapproved HTTP routes.

## Implementation
- backend/src/main/java/com/argdigital/aronexa/encounter/EncounterDraftContract.java: immutable internal and side-effect-free draft contract using canonical tenant, branch, patient, encounter and assigned doctor UUIDs. Four states: CHECKED_IN, CONSULTATION_DRAFT, RX_DRAFT_LINKED, BILL_DRAFT_LINKED. Bounded note, optimistic revision and invalid-transition rejection, log redaction. Draft references are NOT final prescriptions/invoices.
- backend/src/test/resources/db/test/encounter-proof.sql: **disposable Testcontainers-only** schema in encounter_proof (not a production migration). The synthetic non-owner, non-BYPASSRLS test role has transaction-scoped tenant/branch/doctor RLS, identity-column update restrictions and canonical patient/branch/doctor foreign keys, plus metadata-only append audit table.
- backend/src/test/java/com/argdigital/aronexa/encounter/EncounterDraftPostgresProofTests.java: disposable PostgreSQL 17 fixture tests for cross-tenant/branch/doctor denial, no-context denial, restricted privileges, optimistic revisions, audit and transaction-local isolation.
- backend/src/test/java/com/argdigital/aronexa/encounter/EncounterDraftContractTests.java: pure contract acceptance for scope, ordering, immutable snapshots, invalid notes, stale revisions and log redaction.

## Execution and evidence
Standard GitHub workflow runs Maven verification, Flyway and PostgreSQL Testcontainers integration, frontend build, docs/scope and secret checks. Targeted developer command from repo root:
    mvn -B -f backend/pom.xml -Dtest=EncounterDraftContractTests,EncounterDraftPostgresProofTests test

**At authoring time:** exact-head Maven test results and current CI **NOT RUN**; never infer PASS. On failure record findings accurately and repair narrowly, without changing the original Windows database. No current QA Central plan is registered for this encounter contract.

## Security boundaries
This is not a production clinical service or persistent encounter API. It has no live authenticated session gate, branch membership/module entitlements, patient-object authorization, verified revocation handling, idempotent command journal, published prescription finalization, transactionally enforced audit writer or concurrency-ready production service.

Transaction settings obtained from current_setting are **forgeable by a stolen SQL role**. The RLS tests demonstrate scoped behavior under a trusted server-side binding assumption, not resistance to a compromised login. Real server code must derive tenant/membership from verified authentication, check branch/doctor/patient/module ownership in DB per command, and deny all affected APIs until independently reviewed.

The audit example is explicitly written inside the test transaction; no mandatory database audit trigger is yet part of a production persistence API. Real clinical/financial writes need immutable audit semantics and hard failure on missing audit.

No real patient data, original Windows database, new Flyway migration, customer account, API endpoint, production DB grant, signing, print, user interface, payment posting, SaaS hosting or installer was touched.

Holds: #45 independent registration security, #31 original Windows V8 to V9 W3-E/W4, special PR #36/#38 gates and #63 manual routine merge process remain binding. Offline-to-cloud sync is NOT claimed.

## V3 section 22 — 16-item deliverable
1. Phase: COMM-V1-T07, internal encounter contract, Phase 01 to 03 interface.
2. Baseline: current main code inspected; isolated branch/worktree contract.
3. Delivered: internal immutable contract and synthetic PostgreSQL security tests.
4. DB/API: test-only schema and no public HTTP endpoint.
5. UX/Ink: no change, separate #83/#78 tasks.
6. Security: preliminary RLS proof; production authentication/revocation NOT RUN.
7. Functional QA: JUnit suites authored; actual results pending CI.
8. Regression: CI Maven/frontend checks pending.
9. India/clinical/privacy review: NOT RUN.
10. Deployment: no Windows or SaaS customer edition from this PR.
11. Artifacts: internal Java model, two test classes, test SQL fixture and this handoff.
12. Risks: untrusted SQL GUC, no real audit binding, auth or production migrations.
13. Backup/restore and license acceptance: NOT RUN.
14. Doctor/frontdesk/customer UAT, keyboard/NVDA: NOT RUN.
15. Release classification: INTERNAL_FOUNDATION / NOT_READY.
16. Disposition: READY FOR DRAFT REVIEW + DISPOSABLE CI; NOT READY FOR COMMERCIAL RELEASE.

## Next bounded work
After exact-source CI review, authorize a secure Spring encounter service with currentPrincipal-derived tenant identity, branch/doctor/patient ownership checks, enabled OPD module, transactional idempotency, audit, revised Testcontainers negatives and a separately reviewed production migration. Start Windows installer/licensing/recovery proof in a separate disposable branch (#74); preserve security and release approvals.
