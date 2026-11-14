# PILOT-08 RC03 — Browser-created registration → booking/check-in → separate-JVM recovery

**Milestone:** [Integrated Reception Pilot Candidate #135](https://github.com/ARG-Digital-Solutions/aronexa-healthcare-platform/issues/135)  
**Parent source:** owner-reported PASS of RC02B Draft PR #138, exact SHA `d5145a01afb007e7f18d18cd694e44c2fbf6bef0`.  
**Classification:** INTERNAL SYNTHETIC TEST FOUNDATION / NOT CUSTOMER READY.  
**Current source QA:** NOT RUN on this new RC03 SHA. No manual clinic UAT or external security review has been performed.

## What changes in the evidence

Prior Pilot-05/RC01 restarts seeded the **patient and registration directly in SQL**. Prior Pilot-06/RC02B Chromium tests exercised authorized search, doctor selection, queue/check-in and Doctor/Rx/Billing against fixture-seeded registration. Neither by itself proved that the patient was actually **created by the browser** and then recovered after a distinct JVM exit/start.

RC03 adds a **separate opt-in test mode** to the existing already guarded Pilot-05 two-JVM harness. The run still verifies a unique container label and out-of-band UUID marker **before any Spring datasource/Flyway connection**, uses one Docker `postgres:17-alpine` container bound only to loopback, and refuses original database names/ports/inherited credentials. The RC03 mode is separately flagged and does not run in the prior Pilot-05/RC02B normal runner.

### Phase A — first JVM, actual Chromium writing data

1. Stage existing appointment proof SQL and the review-only V10 registration writer **solely in the out-of-band-verified disposable database**. The `RegistrationCandidateHttpAcceptanceTests.CandidateConfiguration` and its nested POST controller are exposed for explicit test-only `@Import`; the key installer passes an in-memory per-JVM random 256-bit proof key to `RegistrationDatabaseProvisioner` inside an explicit disposable-owner transaction. No production key configuration, Flyway migration, Windows database or customer service is touched. Profiles remain `test`, `registration-candidate`, `appointment-candidate` and cannot be enabled for `prod`/`dev`.
2. Seed ONLY a fictional tenant, staff accounts, enabled modules, branch assignments, doctor role and minimum Reception permissions. Assert `SELECT count(*) FROM patient WHERE tenant_id=?` is **zero** before the browser begins. Unlike Pilot-05, there is no SQL-seeded patient, patient identifier or registration in the RC03 fixture.
3. Launch the already-built connected Reception test preview on loopback port 5176, proxying to a random-port local Spring Boot JVM. Launch real headless Chromium from the verified QA-Central Playwright installation; credentials are delivered through process stdin, never logged. Staff logs in, fills fictional name, reviews, confirms registration and receives server-issued patient/registration UUID and generated MRN from an actual HTTP 201.
4. Chromium triggers the existing registration-read authorization, selects a branch doctor from the authorized server directory (no UUID typing), chooses an available slot, sends the real test-only HTTP POST booking (201), then HTTP check-in (200). It loads the daily queue and opens the same registration from that queue through a second authorized patient-role HTTP read. It signs out/in and attempts the **same fictional patient registration again**, which must be rejected with HTTP **409 `DUPLICATE_REVIEW_REQUIRED`**, proving no second identity is silently created.
5. The browser outputs only the three pseudonymous server-issued UUIDs and visit date as an internal completion marker; no synthetic patient names, staff passwords, tokens, clinical content or response bodies are printed. The Java harness independently checks exactly one patient row, exactly one linked registration/MRN, a `CHECKED_IN` appointment with matching tenant/branch/patient/registration/date, and exactly two append-only booking/check-in events. It records the same IDs plus first JVM PID in the existing verified disposable database marker, then the JVM exits.

### Phase B — new JVM, same marked database

1. The PowerShell runner starts a **different Maven/Surefire Java JVM** using the same exact container ID, labelled run UUID and loopback PostgreSQL port; database marker preflight is repeated before any Spring context.
2. There is **no patient fixture or API creation** during Phase B. Using the original marker, the JVM asserts a different OS PID and verifies the fictional name and exactly one patient record, then creates a fresh receptionist bearer and performs a real authorized registration GET for the **browser-issued patient/registration**.
3. The new JVM GETs that visit date's daily queue, verifies the exact **browser-issued appointment**, patient, registration and `CHECKED_IN` stage, checks previous-day exclusion and immutable event count, then revokes the session and proves reuse is denied. It changes the marker to `PASS_B` with the second JVM PID. The PowerShell runner independently asserts `PASS_B|t`, saves both Surefire XML reports and cleans up only the validated disposable container.

This is **two JVMs with a persistent disposable PG17 container**, not a Windows service reboot, another physical machine, a signed installer or backup/restore proof.

## Windows QA — exact head only

On a fresh detached clean checkout of the current Draft RC03 PR:

```powershell
$sha = '<CURRENT_RC03_PR_HEAD_SHA>'
$qaCentral = Join-Path $env:USERPROFILE 'Application Development\AroNexa-QA-Central'
powershell.exe -NoProfile -File .\scripts\pilot\Test-AroNexa-Pilot08-RC03-BrowserCreatedRestart.ps1 -ExpectedCommit $sha -QaCentralPath $qaCentral
if ($LASTEXITCODE -ne 0) { throw 'PILOT08 RC03 QA FAILED' }
```

The **single guarded** top-level runner executes:

- **Stage 1:** full RC02B → RC02A → RC01 → PROD-INT-02 tests on exactly the new SHA (frontend tests and connected build, real HTTP/disposable PG17 authorization and privacy/role negatives, earlier Chromium, one prior distinct-JVM recovery).
- **Stage 2:** a fresh independent Pilot-05-labelled disposable PG17 container with Phase A browser-created registration/duplicate-review/booking/check-in and Phase B different-JVM re-read. Both phase Surefire XML files must report exactly one test and zero failures/errors/skips. The database marker must show different OS process IDs. Run report is saved under `%USERPROFILE%\AroNexa-Local-QA\PILOT05-<run-guid>\`.

Expected final marker **only if both stages complete**:

`PILOT08 RC03 BROWSER-CREATED RECEPTION JOURNEY / DISTINCT JVM QA PASSED`

The RC03-specific second runner also emits:

`PILOT08 RC03 BROWSER-CREATED REGISTRATION / DISTINCT JVM RECOVERY QA PASSED`

**No PASS is claimed on the current new RC03 source.** The owner's earlier RC02B PASS does not certify it.

## Remaining commercial blockers

**Do not merge, retarget, activate, deploy, touch original DB, store real patient data, issue real prescriptions or present this as a customer release.**

- Patient MRN/name search remains a **test-only privileged-function prototype**: needs independent SQL owner, enumeration/rate-limit, privacy/consent, branch/RLS and production forward migration review.
- Production booking and doctor directory are still **disabled by default** and lack reviewed production writer/key provisioning.
- Existing manual user sign-in still asks for branch UUID (the test fixture provides it). A practical product must offer an authorized clinic/branch chooser.
- Clinical Doctor/Rx/Billing journey remains **test-profile** and requires production-source authorization, versioning and staff usability tests.
- Original Windows DB HOLD #31, independent security/revocation reviews #45/#123/#125 and R1-09 release NO-GO.
- Signed offline Windows installer, licensed service ownership, encrypted **nonempty** DB backup/restore on another device, human receptionist/doctor clinical UAT and independent accessibility/NVDA UAT not passed.

RC03 would close the **test-only browser-created registration → booking/check-in → separate-JVM persistence** gap within #135 if the user reports a successful new-SHA QA. It does NOT close all commercial release requirements or turn the disposable prototype into deployable software.

## 16-field handoff

1. Phase: PILOT-08 RC03, complete browser-created fictional Reception proof.
2. Accepted parent: RC02B exact SHA reported PASS, Draft/unmerged.
3. Scope: real Chromium registration POST 201, duplicate-review 409, doctor picker, booking POST 201 and check-in POST 200.
4. Database: same labelled disposable PG17 across separate JVMs; review-only V10 SQL test installation, no production migration.
5. Identity: server-created MRN, patient UUID, registration UUID; no seeded patient identity.
6. Authorization: existing bearer, active role/module, branch, patient restricted SQL read and test-only write SQL; unchanged release holds.
7. Negative: duplicate identity refusal, revoked session, source SQL count and wrong-day queue exclusion.
8. Frontend: connected Reception test page, no manual patient/doctor UUID entry during browser journey; branch UUID still test fixture input.
9. Test runner: exact-SHA, clean tracked source, two complete regressions plus RC03 different-JVM test.
10. QA: authored; NOT EXECUTED on new SHA.
11. Evidence: two Surefire XML files, marker/different PIDs, per-run summary; no patient name/token printed.
12. Recovery: same disposable container after Java process restart; not real Windows service/second-device recovery.
13. Clinical: no real prescriptions, billing collections, doctor release or clinical UAT.
14. Security: independent sign-off pending.
15. Classification: INTERNAL FOUNDATION / NOT_READY.
16. Next action: owner local exact-SHA QA; remediate bounded failures without weakening original DB/security holds.
