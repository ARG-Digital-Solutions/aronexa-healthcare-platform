# PILOT-08 RC01 — Integrated Reception queue continuity + separate-JVM persistence

**Parent milestone:** [Issue #135](https://github.com/ARG-Digital-Solutions/aronexa-healthcare-platform/issues/135) (owner-authorized 14 November 2026).  
**Stacked baseline:** owner-reported PROD-INT-02 exact-SHA technical acceptance `b0baf670fa90ad3ba13af6fee3629aa24eed2e23`, Draft PR #134.  
**Classification:** INTERNAL_FOUNDATION / NOT READY FOR CUSTOMER USE.  
**Scope:** first bounded execution slice for #135; **not** completion or release acceptance of the whole milestone.

## Verified upstream state at planning

- Pilot-05 (#130) distinct JVMs on marked disposable PG17 — previously accepted, but original proof focused on consultation.
- Pilot-06 (#131) one-tab real Chromium → Spring test HTTP → disposable PG17 through Reception/Doctor/Rx/Billing — previously accepted; patient/booking had been SQL/HTTP fixture-seeded before browser started.
- Pilot-07 (#132) internal Windows fictional test lab and nonempty *unencrypted* same-container PG17 restore — previously accepted technically.
- PROD-INT-01 (#133) actual production-source authenticated intake read — owner-reported PASS.
- PROD-INT-02 (#134) default-disabled source booking/check-in HTTP with restricted SQL role in disposable tests — owner-reported PASS. No production writer or customer activation.
- Security issue #123 and design PR #125 remain open; original V8→V9 database hold #31, independent review #45, installer/licensing/encrypted recovery and R1-09 commercial NO-GO continue.

## Increment implemented

1. **Single-screen Reception navigation from daily queue:** a queue row now offers **Open verified registration**. This button is displayed only for signed-in staff with `PATIENT_REGISTRATION_READ`. It does **not** trust the queue's patient ID: it performs a fresh authenticated `GET /api/v1/branches/{branchId}/patient-registrations/{registrationId}` through the production-source read controller, the dedicated `aronexa_patient_runtime` role and branch/tenant PostgreSQL RLS; it compares four IDs with the signed-in tenant, current branch, and queue row before navigation. A mismatch, stale session or denied registration clears the results, shows a generic error and requires queue refresh. No names, contacts or clinical details are read; no tokens are stored.
2. **Strict continuity unit tests:** `queueRegistrationContinuity.ts` and `queue-registration-continuity.test.mjs` refuse changed patient, registration, tenant, branch, invalid UUID and malformed/null link. No queue/appointment IDs are passed through the new registration-reference object.
3. **Real Chromium proof:** only the isolated test `pilot06BrowserIntegratedRealHttpAcrossThreeStaffRoles` provisions patient read permission and module entitlement for fictional accounts inside disposable PG17. The existing headless one-tab browser run now uses **Open verified registration** on the selected queue row, waits for server-verified registration, asserts that the manual UUID input is absent, returns to the daily queue and manages/checks in the same appointment before continuing Doctor/Rx/Billing. It confirms an actual successful registration-read HTTP request and saves a private screenshot of the **fictional-only authorized registration view** under an ephemeral owner-only evidence folder.
4. **Separate-JVM receptionist recovery:** extend the already isolated `Pilot05SeparateJvmHttpTests` Phase A to verify authorization/audit for the SQL-fixture-seeded registration, real HTTP booking/check-in and today's checked-in queue before shutdown. Phase B starts a **different Java JVM process**, uses the **same labelled disposable PG17 database/run marker**, signs into a new receptionist session, looks up the same registration over the dedicated patient-read role, checks the checked-in daily queue and an excluded adjacent date, checks append-only read audit, and separately verifies the existing Doctor consultation draft and role denial. The existing proof asserts different JVM PIDs and validates the marker; no service restart or permanent database is implied.
5. **Fail-fast one-command QA:** `scripts/pilot/Test-AroNexa-Pilot08-ReceptionContinuity.ps1` enforces exact SHA, clean tracked source and no inherited datasource/session settings; runs (1) full PROD-INT-02 production-source security regression, (2) real Chromium browser/HTTP/PG17 integration with screenshot, and (3) two distinct JVM real HTTP persistence phases using labelled, automatically removed Docker PG17. A private evidence summary is written under `%USERPROFILE%\AroNexa-Local-QA\PILOT08-<sha12>-<guid>\SUMMARY.txt`, with screenshot. No passwords, tokens, actual patient data or signing keys are written to this evidence.

## Local Windows acceptance command

Use a NEW clean detached worktree at the exact head of this new Draft PR. From that root:

```powershell
$sha = '<LATEST_PILOT08_DRAFT_PR_SHA>'
$qaCentral = Join-Path $env:USERPROFILE 'Application Development\AroNexa-QA-Central'
powershell.exe -NoProfile -File .\scripts\pilot\Test-AroNexa-Pilot08-ReceptionContinuity.ps1 -ExpectedCommit $sha -QaCentralPath $qaCentral
if ($LASTEXITCODE -ne 0) { throw 'PILOT08 RC01 FAILED' }
```

Expected final marker **only if all three stages exit 0**:

`PILOT08 RC01 RECEPTION QUEUE + DISTINCT JVM RECOVERY QA PASSED`

The new SHA has **not** been run on the owner's PC or independently browser verified. Do not claim a PASS in GitHub until the owner supplies the complete success marker and the evidence folder. Also record human keyboard usability observations separately; automated Chromium does not count as clinical UAT.

## #135 definition of done — actual scope ledger

| Acceptance requirement | Status after source implementation |
|---|---|
| Authenticated server Reception with patient read/queue permission | Implemented in existing test-only UI; prior QA, new SHA pending |
| Open existing patient from a daily queue without copying UUID | Implemented, new SHA browser QA pending |
| Find arbitrary patient by name/MRN when no booking exists | **NOT IMPLEMENTED**; cannot access demographics through patient role without reviewed search contract |
| Register a new fictional patient using browser and duplicate review | Existing test-only flow, **not included** in this new browser acceptance |
| Choose doctor by name rather than membership UUID | **NOT IMPLEMENTED**; needs approved branch-scoped clinician directory |
| Book and check in through server-connected persistent HTTP | Existing test-only browser flow and default-OFF source contract; new SHA QA pending |
| Authoritative daily queue, fail-closed stale/wrong date | Existing backend queue; date-exclusion restart assertion added |
| Different Java JVM PIDs, same marked disposable PG17, persistent registration/booking/check-in/queue | Extended tests implemented, **new SHA QA pending**; registration originally SQL-seeded |
| Screenshots, exact SHA, test counts and logs | Synthetic screenshot + local Surefire/JVM evidence configured; not run |
| Real installed Windows service restart | **NOT PROVEN** |
| Production schema/role review, encryption, license and customer UAT | **NOT APPROVED**; NO-GO |

## Immediate follow-up slices

**RC02 — Test-only search and clinician selection:** a reviewed, privacy-aware, branch-scoped patient MRN/name search with exact/normalized query, pagination/rate limiting, duplicate-candidate handling, nonowner RLS and audit, plus an authorized branch-doctor directory. Avoid querying patient/identifier tables through the privileged test migrator. The receptionist must see safe descriptors without copying UUIDs.

**RC03 — End-to-end new patient browser + restart:** automate one session from sign-in → fictional search → duplicate-review registration POST → doctor selection → booking → check-in → queue; then restart a second application JVM and reverify the **server-created** registration/appointment/queue with a fresh login. This will close the remaining #135 client flow gap when all negative cases and evidence pass.

**Commercial path remains separate:** production migrations, restricted key custody and authorization/revocation review (#123/#125), owner original DB approval (#31), security (#45), signed Windows installer/licensing, real nonempty encrypted backup and second-location restore, human clinical/accessibility UAT and explicit owner release GO.

## V3 16-field handoff

1. Phase: PILOT-08 RC01 — integrated Reception queue continuity and process restart.
2. Source: PROD-INT-02 approved technical test baseline and #135 latest owner milestone.
3. Features: authorized queue-to-registration navigation, no copied patient UUIDs for that path.
4. DB/API: existing production registration-read API, test-only appointment GET/POST; no new migration.
5. UX: one Reception browser tab; queue row links to server-verified registration.
6. Security: fresh patient-role SQL read/audit, strict identity checks, no storage, fail-closed denials.
7. Tests: new frontend negatives; modified Chromium real HTTP; extended two-JVM registration and queue proof; NOT RUN on new SHA.
8. Regression: full PROD-INT-02 source security QA + Pilot06 browser + Pilot05 JVM runner required at new head.
9. Compliance: fictional data only; no clinical issuance or patient data.
10. Deployment: internal disposable QA on Windows only, no clinic machine installation.
11. Artifacts: React, TS helper/unit test, Chromium flow, JVM assertions, one-command script, screenshot and report on PASS.
12. Risks: role/branch doctor picker and arbitrary patient search absent; restart registration SQL-seeded; browser new registration unverified.
13. Recovery: same disposable PG17 two-JVM persistence, not actual Windows reboot or encrypted backup.
14. Human UAT: NOT RUN.
15. Classification: INTERNAL_FOUNDATION / NOT_READY.
16. Handoff: Draft/unmerged new SHA local QA pending, #135 remains open.
