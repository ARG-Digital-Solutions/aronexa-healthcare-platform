# PILOT-08 RC02A — Branch doctor selector without manual UUID

**Parent:** owner-approved Integrated Reception Pilot Candidate [#135](https://github.com/ARG-Digital-Solutions/aronexa-healthcare-platform/issues/135).  
**Baseline:** accepted RC01 Draft PR #136, exact SHA `b417b951f541a95ecc511cc1c4c337f59c2c652d`.  
**Classification:** INTERNAL_FOUNDATION / NOT_READY, test-only activation and fictional data.  
**Current code:** authored, **QA NOT RUN on this new source SHA**.

## What this increment adds

- An actual `src/main` read-only clinician directory endpoint `GET /api/v1/branches/{branchId}/opd/doctors`, **default disabled** by `aronexa.opd.directory.reviewed-activation=false` in BOTH controller registration and Spring Security. No production property, migration, or staff grant is changed.
- The service requires current authenticated bearer principal, `APPOINTMENT_BOOK`, live OPD entitlement and staff-assigned branch through the existing tenant-scoped app runtime JDBC transaction. It reads at most 100 candidate memberships through existing app-runtime RLS grants, then only active `DOCTOR` role accounts through the **separate authentication-runtime read-only JDBC credentials** and returns no more than 25 intersecting doctor membership IDs with staff username labels. No patient or clinician contact details, passwords or role management are read or exposed.
- Because these are two separate JDBC connections without reviewed cross-datasource revocation locking, **#123/#125 independent concurrency review remains a hard customer-release/activation blocker**. This implementation is not approved for production use. A future dedicated scope-locked clinician directory may replace it.
- React's isolated connected Reception preview now loads the available doctors from server before entering the Booking screen; the clinician is chosen from a select menu, not an input into which staff type a UUID. If the list is unavailable/empty or the session changes, booking cannot proceed; no cached/fallback doctor identity is invented. All data remains in component memory, erased on signout.
- `doctorDirectoryClient.ts` strictly validates UUIDs, staff labels, count, duplicates and 401/403/5xx denial; generic failures prevent leaking server errors. Unit tests cover spoofed lists, missing permissions and no-fallback behavior.
- Real-HTTP/PG17 candidate tests enable the endpoint **only inside disposable appointment-candidate Spring test profile**, verify active branch doctor listing, missing bearer, wrong branch, absent booking permission and disabled OPD module. An independent `OpdIntakeProductionHttpAcceptanceTests` negative verifies normal profile still returns 403 even to permitted staff.
- Real Chromium test (the existing one-tab fictional Reception→Doctor→Rx→Billing flow) clicks **Book test appointment** after server-verifying a registration, waits for a populated `select#appointment-doctor` with a real server-issued doctor choice, and navigates back to queue without manually entering a membership UUID.
- The previous RC01 distinct-JVM same disposable PG17 registration/queue recovery proof remains required.

## Exact source Windows acceptance

Fetch the exact latest Draft PR head and run this from a **clean detached worktree**:

```powershell
$sha = "<LATEST_RC02A_PR_HEAD_SHA>"
$qaCentral = Join-Path $env:USERPROFILE "Application Development\AroNexa-QA-Central"
powershell.exe -NoProfile -File .\scripts\pilot\Test-AroNexa-Pilot08-RC02A-DoctorPicker.ps1 -ExpectedCommit $sha -QaCentralPath $qaCentral
if ($LASTEXITCODE -ne 0) { throw "PILOT08 RC02A acceptance FAILED" }
```

The runner first executes a focused **real HTTP** and **automatically disposable PG17** doctor-directory test, checks Surefire 1/1, and then invokes the entire parent RC01 guard at the **same SHA**: frontend tests/build; PROD-INT-02 production-source restricted-PostgreSQL/security acceptance; real Chromium no-copy Reception and doctor selector; two different JVM processes with the same marked disposable PG17, recovered registration/booking/check-in/queue and fictional-only screenshot.

Only a complete clean run emits:

`PILOT08 RC02A BRANCH DOCTOR SELECTOR + RECEPTION CONTINUITY QA PASSED`

**The new SHA has not been locally run by the assistant** and the parent PASS on previous SHA does not establish acceptance of this increment.

## Explicit #135 gaps after this slice

| Acceptance | Status |
|---|---|
| Reception opens queued patient registration without typing UUID | Owner-reported RC01 PASS |
| Select doctor without typing membership UUID | **Implemented, new QA pending** |
| Branch doctor names from reviewed clinical roster | **Not delivered**; only staff usernames on test role |
| Arbitrary patient name/MRN search without prior appointment | **NOT IMPLEMENTED**; dedicated patient role intentionally has no SELECT on patient or patient_identifier as of V9 |
| Browser-created fictional registration with reviewed duplicate handling | Existing candidate UI, not yet covered by combined no-copy browser acceptance |
| Fresh-JVM readback of a **browser-created** registration | **NOT PROVEN**; current restart fixture seeds registration via SQL |
| Installed real Windows service start/restart, signed license, encrypted second-device nonempty restore | **NOT DELIVERED** |
| Independent session/revocation, SQL and clinical/security review | **OPEN** (#45/#123/#125) |
| Customer clinic deployment/merge/release | **NO-GO** |

**Next RC02B:** design and test a nonowner PostgreSQL patient search capability (prefer normalized exact MRN first, then privacy-reviewed patient name lookup) with minimum necessary identity fields, paginated/rate-limited responses, matching-branch authorization, consent/duplicate workflow and auditable reads. V9 deliberately denies patient/identifier SELECT: do not bypass that by using the test migrator, autogranting roles or enabling an unauthorized migration. This requires a separately reviewed forward-only migration/design, initially tested on disposable DB only.

**Then RC03:** one browser workflow new fictional patient → duplicate review → registration POST → doctor selection → booking/check-in → daily queue → different JVM process restart, with no manual UUID entry and exact-SHA automated evidence.

## 16-field handoff

1. Phase: PILOT-08 RC02A branch-scoped clinician selector.
2. Baseline: RC01 accepted owner-reported exact SHA.
3. Feature: booking screen selects active doctor by staff username, not UUID.
4. API: default-off read-only doctor directory; no new migration or writes.
5. UX: isolated Reception select, fail-closed on unavailable list.
6. Security: current permission/module/branch, separate restricted app/auth DB readers, no fallback; revocation atomicity review still needed.
7. Tests: client unit, real HTTP/PG17, Chrome and parent restart regressions authored; **NOT RUN on new SHA**.
8. Regression: entire RC01 runner required at exact source.
9. Compliance: fictional users only, no patient/clinical data or staff contacts exposed.
10. Deployment: no production activation or Windows installer.
11. Artifacts: Java controller/service, React client/selector, unit/HTTP/browser tests, QA runner and this guide.
12. Risks: auth/app two-connection concurrency, user name labels, >25 doctors/100 branch members denied without paging.
13. Recovery: RC01 distinct-JVM disposable same database; not real offline recovery.
14. Human UAT: NOT RUN.
15. Classification: INTERNAL_FOUNDATION / NOT_READY.
16. Handoff: owner local exact-SHA QA pending; issue #135 open.
