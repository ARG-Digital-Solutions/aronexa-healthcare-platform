# R1-PILOT-07 — Assisted Windows OPD Test Lab and Disposable Recovery Rehearsal

**Classification:** INTERNAL_FOUNDATION / NOT READY FOR CUSTOMER.  
**Based on:** owner-accepted test-only Chromium real-HTTP R1-PILOT-06, Draft PR #131, source `901a731e3fab37db79d10b7b09d77f9bb6804b3e`.  
**Owner intent:** deliver a tangible single-PC Windows experience quickly with ease of use and data security as primary priorities.  
**This branch:** source implementation only; Windows QA has **NOT RUN** at this branch's new SHA.

## Product truth

The accepted Pilot-06 work proves real browser behavior for a *test-only* Spring HTTP application and disposable PG17 fixture. Its appointment, consultation, prescription draft, and billing endpoints remain in `backend/src/test/java` with a test-specific profile and test-only SQL. They are **not** in the approved commercial runtime. No safe "installer" may enable those test-only controllers for patient use without design/authorization review. Building a production Spring JAR alone does **not** produce the accepted OPD workflow.

This milestone delivers an internal **AroNexa-Clinic-OPD-TestLab** for owner/staff usability exploration without claiming a deployable clinic product.

## Deliverables

1. `Start-AroNexa-Pilot07-DisposableWindowsLab.ps1`: exact source SHA, clean tracked tree (or verified archived-source mode), explicit `-ConfirmFictionalDisposableLab`, Windows-only, Java 21/Maven/Docker/Node required, inherited datasource/profile rejected; builds the connected Reception app and executes a held-open JUnit random-port backend. No patient DB or production API is started.
2. `pilot07OperatorInteractiveDisposableWindowsClinicLab` JUnit method: creates a fictional receptionist, assigned doctor, billing account, registration and a BOOKED appointment; **automatically disposable** PostgreSQL 17 via Testcontainers, loopback-only Spring server and Vite Preview, using the same accepted connected UI as Pilot-06. Writes one temporary **fictional-only** sign-in sheet under a uniquely created `%LOCALAPPDATA%ARG-DigitalAroNexa-Pilot07-Disposablepilot07-<GUID>` folder. The sheet contains only synthetic test account names/passwords, branch and appointment UUID, never actual clinical or billing records. It attempts to open the URL in the local browser; otherwise use the printed address.
3. Manual session remains open **up to 25 minutes**, or ends when the operator creates `STOP` in the printed run folder. The JUnit test then shuts down its Vite child and Testcontainers destroys the database; the fictional credentials sheet is deleted.
4. `Test-AroNexa-Pilot07-DisposableRestore.ps1`: a **separate** focused JUnit disposable backup rehearsal creates one nonempty fictional checked-in consultation via authenticated HTTP, invokes `pg_dump -Fc` **inside** the already disposable container, restores into a **different fresh database in the same container**, compares a specific appointment and consultation revision, and deletes both backup file and restored database. The source PostgreSQL database and container are automatically destroyed by Testcontainers after the test. This is **unencrypted test-data proof** only.
5. `Build-AroNexa-Pilot07-WindowsTestKit.ps1`: assembles a private ZIP named `AroNexa-Clinic-OPD-TestLab-<sha12>.zip` with tracked source at the exact Git SHA, prebuilt connected React bundle, guarded operator scripts, synthetic-only readme and a SHA256 file inventory. It reports the ZIP SHA256. A source-only ZIP is **NOT a signed installer**: the extracted kit still requires Java 21, Maven, Node/npm and Docker and is only for a disposable internal test PC. Keep the ZIP private: it contains development source. Do not share it with a clinic/customer.
6. `Test-AroNexa-Pilot07-Archive.ps1`: checks all archive-source hashes and refuses unmanifested files; this detects accidental changes but **does not authenticate a publisher**. Preserve the ZIP SHA256 separately.

## Exact-SHA Windows acceptance

Use a fresh detached worktree at the Draft PR's current exact head SHA (not the prior Pilot-06 SHA). Install Java 21, Maven, Node 24, Docker Engine and Git on the internal Windows QA machine.

### Step A — nonempty synthetic recovery rehearsal

```powershell
powershell.exe -NoProfile -File .\scripts\pilot\Test-AroNexa-Pilot07-DisposableRestore.ps1 -ExpectedCommit <PR_HEAD_SHA>
if ($LASTEXITCODE -ne 0) { throw 'PILOT07 disposable restore FAILED' }
```

Required markers include `PILOT07_DISPOSABLE_NONEMPTY_PG17_RESTORE_PASS` in JUnit and `PILOT07 DISPOSABLE NONEMPTY PG17 RESTORE QA PASSED` from the guarded runner. **This is not a clinic backup or AES-GCM encrypted backup test.**

### Step B — interactive OPD session

```powershell
powershell.exe -NoProfile -File .\scripts\pilot\Start-AroNexa-Pilot07-DisposableWindowsLab.ps1 -ExpectedCommit <PR_HEAD_SHA> -ConfirmFictionalDisposableLab
```

The console blocks while the test lab is active. Look for `PILOT07_DISPOSABLE_LAB_READY` and find `FICTIONAL-STAFF-ONLY.txt` in the printed run folder. Reception checks in the prebooked fictional appointment; the assigned Doctor saves a fictional consultation and medicine draft; Billing independently signs in, creates a fictional OPD bill and loads a daily report. The operator records usability defects (no personal or real medical information in screenshots or logs). In a second PowerShell window create the printed `STOP` file to end the session early. A normal session exit prints `PILOT07 DISPOSABLE INTERACTIVE LAB ENDED SUCCESSFULLY`; **that is a technical session lifecycle marker, not clinician signoff or independent UAT**.

### Step C — build the private ZIP artifact

```powershell
powershell.exe -NoProfile -File .\scripts\pilot\Build-AroNexa-Pilot07-WindowsTestKit.ps1 -ExpectedCommit <PR_HEAD_SHA>
if ($LASTEXITCODE -ne 0) { throw 'PILOT07 ZIP packaging FAILED' }
```

The builder emits `Artifact=` and `ZIP_SHA256=`; record both against the exact source SHA. Extract to an **empty temporary Windows QA directory** (no Git checkout needed). Then use the runner's `-FromVerifiedArchive -ConfirmFictionalDisposableLab` flags, with the same exact source SHA. **The bundle does not run without local developer prerequisites.** It is not a Windows installer/EXE.

## Licensing and recovery foundation — explicit holds

Prior unmerged `feature/comm-v1-t09-windows-offline-proof-20261111` demonstrated a *synthetic Ed25519 license verification and AES-GCM sample*. `feature/comm-v1-t10-windows-clinic-package-20261111` created a *jpackage app-image artifact verifier*; `feature/comm-v1-t12-windows-loopback-preview-20261111` served *static foundation UI only*. These are separate feasibility proofs, not a customer entitlement implementation. No old branch is merged or promoted by Pilot-07.

Production offline licensing **still requires** reviewed public-key trust/publisher custody, clock manipulation and renewal handling that never locks existing clinical records. Real encrypted PostgreSQL backup requires online-consistent nonempty backup, AES-GCM/authenticated encryption and external key custody, retention off-device, a second-PC restore and matching patient/audit IDs, interruption/tamper/wrong-key denial. **All such gates remain blocked.** Never store a private license-signing key in a Windows install bundle.

## Clinical and product acceptance boundary

- JUnit and UI use **fictional data only**. Backend appointment and Rx handlers are test-profile candidates, not approved production SQL roles. No clinical prescription is finalized, issued, printed or sent to patients. No cash actually moves; the billing display does not create a statutory invoice.
- No signed MSI/EXE, bundled runtime, restricted PostgreSQL Windows service, license verification, first-run clinic admin, data-preserving uninstall, real encrypted backup or real customer install exists in this milestone.
- This test lab still needs Maven, npm and Docker, therefore does not meet the desired "no developer tools at clinic" packaging criterion.
- The Pilot-07 restore uses one disposable Docker container and a distinct database in it. **It does not prove Windows service restart, physical second-PC recovery, encryption or commercial backup usability.**
- Original database migration HOLD #31, independent security review #45, #123/#125 session/revocation reviews and nine R1-09 NO-GO release gates remain binding; no merge, production migration, installation at a real clinic or customer-release GO.
- After local Pilot-07 acceptance, the production critical path is: approved OPD APIs + restricted PG17/Flyway/RLS runtime, proper installer and service management, signed offline license without clinical lockout, encrypted nonempty backup and independent UAT/security. Do not confuse test-only demonstrations with the commercial release.

## V3 mandatory 16 fields

1. **Phase:** R1-PILOT-07, Windows assisted fictional test lab.
2. **Source baseline:** R1-PILOT-06 owner-accepted test-only source and earlier isolated Windows proof branches reviewed separately.
3. **Feature scope:** manual integrated fictional OPD evaluation and source-only ZIP packaging.
4. **DB/API:** no production code/migrations; only test-profile endpoints and disposable PG17 restoration tests.
5. **UI/UX:** same connected React Reception → Doctor/Rx → Billing UI; manual evaluation pending.
6. **Security:** loopback-only, exact source, temporary accounts, no inherited datasource, archive hash inventory; license/signing/security independent reviews missing.
7. **Tests:** focused restore and interactive lifecycle commands authored; branch exact-SHA Windows execution NOT RUN.
8. **Regression:** Pilot-06 PASS at its previous SHA, not proof of new Pilot-07 SHA.
9. **Compliance:** digital prescriptions, clinical record privacy and financial/invoicing acceptance BLOCKED.
10. **Deployment:** isolated owner-operated Windows test lab only; NO clinic installation.
11. **Artifacts:** three PowerShell runners, source ZIP builder, archive verifier, JUnit proof, this report.
12. **Known risks:** fixed synthetic passwords in ephemeral local file, source ZIP contains private code, dev prerequisites, no production runtime.
13. **Recovery:** nonempty pg_dump/pg_restore rehearsal authored but NOT RUN; commercial encrypted restore absent.
14. **Customer UAT:** NOT RUN; manual fictional usability observations expected.
15. **Classification:** INTERNAL_FOUNDATION / NOT READY.
16. **Handoff:** READY FOR CONTROLLED WINDOWS LOCAL QA, **commercial NO-GO**.
