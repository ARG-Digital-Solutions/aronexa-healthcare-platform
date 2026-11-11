# COMM-V1-T08 — Internal Authenticated Encounter Command Proof

**Parent:** Dev #86, secure encounter foundation #84 and draft PR #85.  
**Status:** INTERNAL_FOUNDATION / NOT_READY. This increment is stacked on unmerged PR #85 head 6f32df9f3a836f0ef963cb0ba1e1aaccc28af97f.

## Commercial objective
Move toward a securely persisted OPD workflow without introducing any production-facing clinical API or migration. Windows offline Clinic Edition is the first customer release; Online SaaS uses shared domain rules but has an independent security gate.

## Bounded implementation
- Internal, non-registered EncounterDraftCommandCoordinator: requires current authenticated membership with explicit OPD_DRAFT_WRITE permission, denies incorrect tenant/doctor/encounter scope, applies revisioned T07 draft-only transitions. NOT an HTTP route or Spring service. No production grant for OPD_DRAFT_WRITE.
- Transaction port contract: adapters must recheck live membership, account enabled state, branch assignment and OPD entitlement in the same restricted SQL transaction as encounter read, update and audit.
- Test-only PostgreSQL script encounter-command-access-proof.sql adds narrowly scoped read/RLS policies to an ephemeral nonowner proof role for user/membership, branch and module. No production privilege, normal Flyway change or original Windows database activity.
- EncounterAuthenticatedPersistenceProofTests: test-only JDBC adapter binds trusted test actor context, enumerates currently assigned DB branches, verifies current user/membership/entitlement and performs a revision-guarded update and a metadata-only audit INSERT atomically. Tests deny wrong tenant, doctor, branch, module off, revoked membership/user/branch, and stale version. T07 canonical patient FK remains enforced.
- EncounterDraftCommandCoordinatorTests: no session, missing permission, password-change-required, tenant/doctor/encounter mismatch and immutable draft-stage negatives.

## Security and clinical LIMITATIONS
- Test principal in Spring SecurityContext is not an actual bearer-token request, and no real auth_session revocation test is included. Real session-bound checks, clinical permissions and encounter ownership policy remain future work.
- A stolen SQL login can forge PostgreSQL custom GUCs (set_config). The synthetic RLS proof demonstrates defense in depth under server-controlled credentials, not immunity to SQL credential compromise.
- No production database schema, API, registered server adapter, real patient data, idempotent command-key replay, mandatory clinical audit trigger, signed/immutable finalized prescription, billing ledger or Windows installer has been implemented.
- Test-only global OPD module enablement occurs only in a fresh disposable PostgreSQL Testcontainer; customer modules remain disabled. No real patient data used.
- No consultation/Rx/billing UI source copied from unmerged PR #75/#78/#79/#83. No original Windows migrations (#31 W3-E/W4 HOLD) or registration activation (#45 independent review). PR #36/#38 special holds and #63 exact-SHA manual merge gate remain binding.

## QA evidence
Expected commands:
- mvn -B -f backend/pom.xml -Dtest=EncounterDraftCommandCoordinatorTests,EncounterAuthenticatedPersistenceProofTests test
- mvn -B -f backend/pom.xml verify
- npm ci && npm run build from frontend
At first authoring: **ALL T08 TESTS NOT RUN**; CI may run after draft creation. Record actual exact-commit job names, test counts, status and sanitized logs, and correct any failures without widening scope. No independent security, clinic usability, Windows recovery or production release acceptance yet.

## Next commercial step
After CI and independent review, authorize a production-scoped encounter adapter with verified bearer/session revocation, server-owned canonical patient/encounter/branch/doctor context, RLS backed by least-privileged runtime, idempotent command journal, immutable audit and test-only migration rehearsal. Production Flyway/original Windows V9 application requires its own explicit GO. Windows installer, signed annual license and encrypted backup/restore continue independently under #74.

## Master V3 section 22 handoff
1. Scope: COMM-V1-T08 internal authenticated encounter proof, Phase 02/03.
2. Baseline: stacked PR #85 head (unmerged).
3. Deliverable: coordinator and disposable SQL authorization adapter tests.
4. Database/API: test-only SQL, no public route or Flyway file.
5. UX/ink: unchanged, separate clinical preview PRs.
6. Security: tests actor/branch/module/doctor/tenant and revocations; production token/GUC-compromise gap remains.
7. New targeted tests authored; exact-head execution pending.
8. Full regression pending.
9. Regulatory/clinical/privacy signoff not run.
10. No Windows/SaaS customer deployment.
11. Four isolated code/test artifacts plus handoff.
12. Risks: SQL role compromise, missing verified production adapter, no idempotent journal.
13. Windows backup/restore NOT RUN.
14. Clinician/finance/a11y UAT NOT RUN.
15. INTERNAL_FOUNDATION / NOT_READY.
16. DRAFT FOR CODE/SECURITY REVIEW; no merge, deploy, original DB apply or customer use.
