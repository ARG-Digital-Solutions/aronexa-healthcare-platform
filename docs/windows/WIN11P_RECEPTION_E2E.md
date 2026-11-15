# WIN-11P Integrated Reception QA

Classification: **NOT_READY — implementation/QA handoff, not completed acceptance**.

## Inspected baseline and minimum gap

Source baseline: `112f106131499a1c47f20bdb8c1f66ab2353b4d3` on
`feature/win11p-portable-java-bootstrap-20261115`. Main is older than the pilot work.
PILOT-06 supplies the connected browser journey, PILOT-07 the disposable Windows
lab, RC02B search and RC03 browser-created records plus separate JVM recovery.
PILOT-09 exposes that fixture for manual testing. WIN-11P packages a normal backend
JAR and Java runtime, but does not package these test adapters.

V1–V9 alone do not expose the candidate registration writer, search function or
appointment proof schema. These are installed only inside the existing guarded
disposable test harness. No review-only SQL is moved into Flyway by this change.

The owner's reported PG17.11 / V1–V9 / RLS / privilege / HTTP401 results for
`aronexa_win11p_loopback_qa` at `127.0.0.1:49742` remain owner-supplied baseline
evidence. This cloud workspace cannot reach that Windows loopback address. This
launcher deliberately provisions fresh isolated databases instead of reusing it.
That is a limitation against the requested existing-database integration.

## Repeatable Windows procedure

Use a clean checkout of the exact delivery commit, full **JDK 21** in `JAVA_HOME`,
Maven, Git, Node 24/npm, Docker Engine and QA Central with Playwright and Chromium
installed. The full JDK compiles source; the existing portable Java runtime runs
the forked backend test JVMs. No paid certificate is required.

From a fresh PowerShell console at this checkout's root, with no inherited
Spring/datasource/test activation overrides:

```powershell
$sha = (git rev-parse HEAD).Trim()
$portable = Read-Host 'Full path to your existing AroNexa portable folder'
$qa = Join-Path $env:USERPROFILE 'Application Development\AroNexa-QA-Central'
powershell.exe -NoProfile -File .\scripts\windows-runtime\Start-AroNexa-WIN11P-Reception-QA.ps1 -ExpectedCommit $sha -PortableDirectory $portable -QaCentralPath $qa -ConfirmFictionalDisposableLab
if ($LASTEXITCODE -ne 0) { throw 'WIN11P acceptance did not complete; retain the evidence folder' }
```

Add `-VerifyOnly` to run automated regression without opening the manual lab.
Do not use a runtime folder as `JAVA_HOME`; the build still requires `javac`.
The portable package's older backend JAR is checked by preflight but **not executed**;
the current source is compiled and exercised through the existing test harness.

The command runs RC03's complete parent regression (including security negatives),
then browser-created registration/search/booking/check-in and a different JVM's
read-back against the same marked disposable PG17. Only after success does it open
a **new** PILOT-09 manual lab at:
`http://127.0.0.1:5176/connected-reception-preview.html`.

Use the fictional staff sheet whose path the launcher prints. Sign in, search,
register a new fictional name, select a doctor, book, check in and load the daily
queue. Use the printed STOP-file command to finish. The lab expires in 45 minutes.

**Persistence boundary:** the automated RC03 test retains its database across two
backend processes. The later manual lab uses a different disposable database and
deletes its data at shutdown. This does not implement a durable installed clinic
runtime or prove restart of manually entered operator records. Do not interpret
the automated persistence proof as that additional feature.

## Evidence and failure handling

The wrapper writes a unique `%USERPROFILE%\AroNexa-Local-QA\WIN11P-<run>` folder
with source SHA, runtime mode, unresolved release gates, summary and transcript.
Child runners print their own evidence folders. Preserve those folders as well:
RC03 writes phase A/B Surefire XML and verifies `PASS_B|t` in its run marker;
RC01 captures a fictional browser screenshot. Never treat an old report as a new
pass. Keep logs private; do not publish credentials or patient-style screenshots.

Failure stops the dependent path; the manual lab does not open after failed
regression. A wrapper PASS would describe only the focused suites actually run.
Prior full-Maven failures remain unresolved until their own exact-SHA rerun passes.

## Remaining completion blockers

- Windows execution of the new wrapper and exact-SHA connected Playwright,
  PostgreSQL negatives and distinct-process evidence must be recorded.
- The normal portable JAR still lacks the guarded candidate adapters; this is
  source-based QA orchestration, not the requested standalone persistent product.
- Reuse of the named existing disposable Windows database requires its actual
  provisioning/marker contract and a reviewed runtime integration; no port-only
  or database-name-only authorization is introduced.
- Operator-created records do not survive shutdown of the manual lab.
- Full Maven QA, independent security review and WIN-14 customer-release approval
  remain open. No merge, deployment, production migration or release approval.

## Read-only verification of owner's existing Windows database (new)

The existing database at `127.0.0.1:49742` is **not** authorized for the automated RC03/manual PILOT09 write suites. Before using the separately provisioned database as a local Spring Boot smoke-test baseline, run this **read-only** acceptance on the owner's Windows machine:

```powershell
$repo = Join-Path $env:USERPROFILE 'Application Development\AroNexa-WIN11P-Portable-QA'
& (Join-Path $repo 'scripts\windows-runtime\Test-AroNexa-WIN11P-ExistingDisposableDb.ps1') -CheckHttp401
```

This enforces exact container name, Docker synthetic-only label, localhost port 49742 and database identity, then checks nine successful V1–V9 migrations, restricted role attributes, RLS table settings, four expected privilege outcomes and optionally the unauthenticated HTTP 401. **It does not test row-level data denial, create accounts or patients, apply review-only V10/V11 SQL, write any tables, or establish persistent Reception E2E.** If the local port/container changes, do not bypass the checks; reassess the disposable identity and review the script.

The old portable source worktree may not contain this script until the branch is fetched/checked out. Keep the new draft PR unmerged and run only on synthetic infrastructure.
