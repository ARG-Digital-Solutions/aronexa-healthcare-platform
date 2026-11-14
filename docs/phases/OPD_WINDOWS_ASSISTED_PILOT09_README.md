# AroNexa Clinic Edition — Windows Assisted Pilot-09 (INTERNAL)

**Not customer-ready. No real patients. This source+prebuilt-React test kit is NOT a Windows installer or a signed executable.**

**Base:** owner-reported RC03 technical acceptance, Draft PR #139, exact source `0f38f86a129d161b747584bc2c57e3727e5d3926`.  
**Related customer-pilot issue:** [#124](https://github.com/ARG-Digital-Solutions/aronexa-healthcare-platform/issues/124); integrated Reception milestone [#135](https://github.com/ARG-Digital-Solutions/aronexa-healthcare-platform/issues/135).  
**Source classification:** INTERNAL_FOUNDATION / NOT_READY_FOR_CUSTOMER.

## What you can test now

The new Pilot-09 launcher brings the **connected** one-tab Reception test UI into a single local Windows operator session backed by a **uniquely labelled, loopback-only, automatically removed PostgreSQL 17 container**. An explicit, source-SHA-checked PowerShell guard prevents connecting to your clinic database. No SQL/migration is applied to the original Windows V8/V9 databases.

The isolated Spring `test + registration-candidate + appointment-candidate` context supplies real HTTP and test-only SQL. Only in the marked disposable container, it installs the existing review-only V10 registration writer, RC02B bounded/audited search function and test-only appointment, doctor/Rx and billing candidates. Search/doctor directory are activated in this isolated JUnit test context **only**, never in production config. The test fixture starts with a fictional tenant, Reception/Doctor/Billing accounts and **zero patient rows**.

Inside the local browser, use fictional data to try:

1. Reception sign-in with the fictitious clinic code and branch UUID printed in the lab-only login sheet.
2. New patient name → Review → Confirm → server-issued MRN/patient/registration; then test duplicate review for the same name.
3. Doctor selection by server-populated dropdown → Book appointment → Check in → load daily queue.
4. Find that same fictional patient by exact MRN or name prefix without an existing queue entry.
5. Doctor sign-in to save a *draft-only fictional* consultation and medicine template; then Billing sign-in to record an imaginary invoice and read a daily summary.
6. Negative usability checks: invalid form, unauthorized role, empty queue, duplicate patient, logout/login and accessibility keyboard navigation. **Do not use real diagnosis, identifiable patient records or make any real payments.**

The lab URL is `http://127.0.0.1:5176/connected-reception-preview.html`. The PowerShell window stays open while the test is running; the launcher prints an explicit STOP-file command. **Do not stop Java/Docker manually unless the guarded shutdown fails.** After STOP or automatic 45-minute expiry, fictional login credentials are deleted and the unique container is stopped/auto-removed. The run retains only local evidence counts and technical logs, not a persistent patient database. This is intentionally NOT a persistent customer installation.

## Prerequisites for your Windows desktop

- Windows 10/11 (internal QA PC, **not a clinic workstation**).
- Java **JDK 21**, Maven, Node/npm (Node 24 recommended), Git when building from source, Docker Desktop/Engine running.
- Internet for the first Maven/npm dependency download and Docker `postgres:17-alpine` image if not already cached.
- Free loopback port `5176`.
- Do **not** set inherited `SPRING_DATASOURCE_*`, `SPRING_PROFILES_ACTIVE`, `ARONEXA_*_DATASOURCE_URL` or Pilot05/09 runtime settings; the launcher refuses them.
- Do **not** modify the source tree while building a pinned kit; no .env or production secret files belong in a kit.

## Build a private downloadable ZIP on your Windows PC

From a **new clean detached Git worktree at the exact current Draft PR head**, run:

```powershell
$sha = "<CURRENT_PILOT09_PR_SHA>"
powershell.exe -NoProfile -File .\scripts\pilot\Build-AroNexa-WindowsAssistedPilotKit.ps1 -ExpectedCommit $sha -DestinationDirectory "$env:USERPROFILE\Downloads"
if ($LASTEXITCODE -ne 0) { throw "PILOT09 KIT BUILD FAILED" }
```

The builder reuses `Build-AroNexa-Pilot07-WindowsTestKit.ps1`, which builds the connected React bundle, archives exactly the tracked Git source at the expected SHA, checks every packaged file with a SHA256 manifest, and creates a private ZIP. The Pilot-09 wrapper gives that ZIP an appropriately labelled filename:

`AroNexa-ClinicEdition-Windows-AssistedPilot-<sha12>-INTERNAL.zip`

The builder prints `Artifact=` and `ZIP_SHA256=`. **Save these values; independently compare SHA256 when moving the ZIP.** It contains private source, including test harness and test-only security candidate definitions; it must **never be forwarded to a client or published publicly**.

The bundle is a source+prebuilt-React **QA kit**, not a compiled native EXE. The extracted kit still compiles and starts the Java test backend with Maven, and uses Docker for a temporary PostgreSQL service. The SHA256 manifest detects package corruption but is **not** a cryptographic publisher signature.

## Simplest way to launch from the ZIP

1. Extract ZIP into a **new empty folder** on your own Windows QA PC.
2. Double-click **`RUN-PILOT09-TEST-ONLY.cmd`**.
3. Read the warning and type **`FICTIONAL`** to confirm the temporary, fictional-only test.
4. If Java/Maven/Docker/npm are available, wait for the console to print `PILOT09_INTERACTIVE_READY` and the path to `FICTIONAL-STAFF-ONLY.txt`. Open the URL above; use the credentials in that file only.
5. Test the workflows listed above. In another PowerShell window run the printed `New-Item -ItemType File -Path '...\STOP'` command to shut down.
6. Keep the technical evidence folder, but **do not share raw credentials, local URLs or unreviewed patient-style screenshots**. No customer approval is implied.

Alternate command from a clean Git checkout at the same SHA:

```powershell
powershell.exe -NoProfile -File .\scripts\pilot\Start-AroNexa-WindowsAssistedPilot.ps1 -ExpectedCommit $sha -ConfirmFictionalDisposableLab
```

Alternate command from a verified extracted ZIP:

```powershell
powershell.exe -NoProfile -File .\scripts\pilot\Start-AroNexa-WindowsAssistedPilot.ps1 -ExpectedCommit $sha -FromVerifiedArchive -ConfirmFictionalDisposableLab
```

## Manual product usability checklist

| Scenario | Evidence to record | Expected behaviour |
|---|---|---|
| Reception first sign-in | Test-only user observation | Valid fictional role; wrong password denied |
| New fictional patient registration | Fictional MRN, screen stage | Confirmed one server-generated patient identity |
| Duplicate name/record | Error code/message only | No silent patient merge or second identity |
| Doctor dropdown | Screenshot with synthetic usernames only | No manual doctor membership UUID |
| Book/check in | Fictional appointment stage | BOOKED then CHECKED_IN, queue consistent |
| Patient search | MRN/name test result | Authorized branch only, 10-result maximum |
| Doctor consultation and Rx | Fictional draft-only indicator | No finalized prescription |
| Billing report | Fictional amount/totals | No actual money/payment |
| Signout and wrong role | Generic denial | No cross-role access |
| STOP and cleanup | Operator log + Docker container exit | Database/temporary credential sheet removed |

No checkbox in this table is automatically marked PASS by opening the test lab. A human operator should record actual observed behaviour, fail states, keyboard issues, confusing labels and screenshots with **fictional-only data**.

## Boundaries — do not misrepresent this as a released product

- **Still missing:** signed Windows installer, reliable automatic OS service/launcher, production-approved authorization/RLS and database migration sequence, rotating keys, encrypted nonempty second-device backup/restore, offline commercial license, long-running persistence between installed Windows launches and approved update/recovery policy.
- **Security holds:** original V8/V9 database HOLD #31; independent review #45 and production session/revocation/race gate #123/#125; R1-09 customer-release NO-GO. This internal test mode does NOT waive any.
- **Clinical:** draft Rx and test bills are not legal clinical prescriptions or payments. The complete OPD production source workflow is **not yet approved or enabled**.
- **Acceptance:** user-provided RC03 technical PASS applies only to prior exact SHA; this new Pilot09 branch requires a clean local build and human UI verification. A successful lab shutdown proves only that the held-open test session ended, **not a clinical release or UAT signoff**.

## Next product increment after owner local test

Build a dedicated, independently reviewed **production-source Windows clinic runtime** with secure app/DB service separation, authorized branch selector and managed PostgreSQL startup, nonempty encrypted offline backup/restore verified on another machine, signed MSI/EXE and license verification, then one-clinic assisted supervised UAT. Do not deploy a test-profile JUnit server as the customer product.
