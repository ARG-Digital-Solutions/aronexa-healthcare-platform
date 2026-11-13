# R1-09 - Windows Clinic Pilot Readiness Gate

**Classification:** INTERNAL_FOUNDATION / NOT READY.
**Parent:** Development R1-08 PR #115 accepted test-only candidate,
SHA 965acbdbe0144e669942d5c30d8a5ce185129950.
**Deployment strategy:** Windows offline assisted install to one pilot clinic.
**Source state:** New R1-09 source not yet run on owner's Windows machine.

## Audited baseline

Owner-reported R1-08 evidence: 277/277 Java tests PASS, authenticated
HTTP and automatically disposable Testcontainers PostgreSQL 17 PASS,
frontend build and Billing/Doctor preview builds PASS. QA Central #36
Chromium 10/10 PASS with MOCKED API replies. All this proof is tied to
the R1-08 exact SHA and does not certify production APIs or an installer.

Current backend production Flyway scripts are V1-V9. R1-04 to R1-08
appointment, consultation, Rx, bill and cash schema, along with associated
HTTP candidate handlers, are in test resources and Java test code.
No real clinic may use these test-only records.

The existing integration/clinic-opd-v1-20261111 branch is separate and
older than the R1-08 acceptance. Compare histories and dependencies
before choosing/merging the customer integration baseline.

Previously authored Windows proof branches should be evaluated and reused:
- feature/comm-v1-t09-windows-offline-proof-20261111:
  Ed25519 verification, 1MiB synthetic AES-GCM fixture encryption,
  jpackage app-image proof. NOT a production license or PostgreSQL backup.
- feature/comm-v1-t10-windows-clinic-package-20261111:
  Spring Boot JAR + compiled frontend app-image packaging, no clinical service.
- feature/comm-v1-t12-windows-loopback-preview-20261111:
  loopback-only static UI proof, no authenticated clinical backend or DB.
These are separate unmerged feasibility increments, NOT installed
customer features. Original Windows migration HOLD #31 and independent
registration security review #45 remain separately binding.

## R1-09 deliverable

scripts/release/Inspect-AroNexa-Windows-Pilot.ps1 is a read-only
exact-SHA gate inventory. It reports a source-cleanliness gate and
nine BLOCKED production/pilot gates:
INT-01 real OPD integration, DB-01 reviewed runtime SQL/migrations,
SEC-01 independent clinical/finance/security review, WIN-01 signed
installer, WIN-02 real encrypted PostgreSQL backup/restore, WIN-03
offline licensing and clinical continuity, WIN-04 upgrade and restart,
UAT-01 clinic/accessibility acceptance, REL-01 owner customer GO.

Default inspection returns exit code 3 on NO_GO. -ReportOnly -AsJson
prints machine-readable JSON and exits 0 solely to capture evidence;
the report STILL says releaseDecision NO_GO. This cannot silently
approve a release. The gate does not start services, contact DB/network,
read patient data, migrate schema, install anything or change Git.

scripts/release/Test-AroNexa-Windows-Pilot-Inspector.ps1 tests exact SHA,
mandatory blocked gates, JSON schema and wrong-SHA rejection.
**Scripts authored but NOT YET EXECUTED on Windows.** They check source
state, not installed Windows runtime or actual crypto/restore readiness.

From a clean exact-SHA R1-09 Windows worktree:

    powershell.exe -NoProfile -File .\scripts\release\Test-AroNexa-Windows-Pilot-Inspector.ps1 -ExpectedCommit EXACT_R109_SHA
    powershell.exe -NoProfile -File .\scripts\release\Inspect-AroNexa-Windows-Pilot.ps1 -ExpectedCommit EXACT_R109_SHA -ReportOnly -AsJson

For a release hard gate, omit -ReportOnly: nonzero exit 3 is expected
until the production criteria are independently evidenced and approved.

## Next implementation sequence (single developer, few interruptions)

### R1-10A - Controlled integrated runtime
1. Compare main, integration/clinic-opd-v1-20261111, Development #115
   and the still-review-gated dependencies before any merge/retarget.
2. Preserve R1-04..08 behavioral acceptance in one approved integration
   branch; build a single receptionist-to-doctor-to-billing workflow.
3. Replace test-only endpoint/SQL scaffolding with explicitly reviewed
   production module contracts, not by blindly copying test controllers.
4. Prove register -> book -> check-in -> consult -> draft Rx -> bill ->
   partial cash -> daily report with fictional data and real API+PG17,
   restart backend, new login and verify exact patient history persisted.
5. Deny wrong tenant, branch, doctor, billing role, revoked sessions,
   forged patient, appointment slot race, stale revisions, cash replay.

### R1-10B - Production database authorization
Define forward-only migrations after V9; independently review
composite tenant/branch/patient foreign keys, RLS, immutable audit and
a separately scoped low-privilege runtime writer. Prevent application
BYPASSRLS, role escalation, DDL or use of migrator credentials in
runtime sessions. Prove SQL/session revocation and pooled isolation.
Original Windows V8->V9 HOLD #31 is NOT lifted by disposable proof.

### R1-10C - Assisted one-PC Windows installer
Use one PC with loopback-only service/database as a DEFAULT BUILD TEST
ASSUMPTION, not a customer workstation decision. If Reception+Doctor
use two PCs, an explicitly approved authenticated LAN/HTTPS design is
required. Reuse reviewed Windows jpackage proof where safe; deliver
actual signed installer, bundled Java runtime, restricted PostgreSQL
Windows service, first-run clinic admin, encrypted config, desktop
shortcut, restart, uninstall data preservation and offline operation.
No Git, Maven, Node or Docker should be required of clinic users.

### R1-10D - License, backup and disaster recovery
Use an independently controlled Ed25519 public issuer trust anchor,
separate signing-key custody, renewal and expiry that preserves access
to existing clinical records. Protect real PostgreSQL nonempty backups
with authenticated encryption, key custody, off-drive retention,
automatic schedules and tested recovery to a SECOND empty installation.
Verify row identities, record history, audit, backup integrity, wrong
key/tamper denial, interrupted restore, disk full and power loss.

### R1-10E - Clinic and external acceptance
On a clean Windows VM prove install, restart and complete offline OPD
workflow with fictional records. Obtain actual independent clinical Rx
safety/signing and finance/statutory invoicing reviews, security,
privacy, keyboard/NVDA and staff usability acceptance. Record artifact
SHA256, training/support plan, controlled upgrade/rollback and
EXPLICIT human release GO before a real patient is entered.

## Execution governance

- First critical path: actual R1-10A/B runtime and SQL authorization,
  not another standalone preview or a demo EXE.
- In parallel, reconcile existing Windows proof PRs and define concrete
  service, license, backup and restore engineering contracts in an
  isolated Windows VM. No original clinic/owner database access.
- Single integration owner; separate clean Git worktree per task.
  Agents do not mutate the same files. Automation records exact
  source SHAs and actual evidence, no fabricated independent review.
- Preserve R1-08 Dev #115 and QA #36 DRAFT/UNMERGED until explicit
  approved conditional merge sequence; no protected workflow change.

## Master V3 mandatory 16-field report

1. Phase: R1-09 Windows pilot readiness.
2. Sources: R1-08 + old integration and Windows proof branch inventory.
3. Features: read-only release preflight and self-test.
4. DB/API: none changed; production runtime remains blocked.
5. UX: no new clinic UI or stylus change.
6. Security: no DB/secrets/PHI access; reviews pending.
7. Tests: self-test authored, Windows execution NOT RUN.
8. Regression: prior R1-08 PASS at prior SHA; R1-09 NOT RUN.
9. Compliance: prescribing/finance/privacy/app installer review pending.
10. Deployment: none; no customer environment touched.
11. Artifacts: PowerShell inventory, self-test, readiness report.
12. Defects: nine hard release blockers and missing real runtime.
13. Backup: synthetic proof exists; real DB restore NOT RUN.
14. Customer/clinical UAT: NOT RUN.
15. Classification: INTERNAL_FOUNDATION / NOT READY.
16. Recommendation: READY FOR READ-ONLY REVIEW, PILOT NO-GO.
