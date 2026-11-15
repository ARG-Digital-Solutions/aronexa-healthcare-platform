# WIN-11P — Portable Java 21 (development bootstrap)

**Classification: NOT_READY.** Owner-approved primary local Windows delivery direction, issue [#144](https://github.com/ARG-Digital-Solutions/aronexa-healthcare-platform/issues/144). This workstream has **no purchased OV certificate prerequisite**. It does **not** relax signed native packaging, clinic release approval, security or clinical controls.

## Deliverables included in this branch
- `scripts/windows-runtime/Build-AroNexa-WIN11P-Portable.ps1` builds and verifies backend via Maven, selects one backend JAR, generates a bundled Java 21 runtime with `jlink`, and emits `Start-AroNexa.cmd`.
- The launcher binds the HTTP server to `127.0.0.1`, requires explicit PostgreSQL datasource environment variables, and refuses to start with missing database configuration.
- Output is placed outside the source checkout with a source SHA, JAR checksum and `NOT-FOR-RELEASE.txt` declaration. Existing outputs are not overwritten.
- No source schema or clinical module changes. Native WIN-11 packaging remains separate and unchanged.

## Build on an authorized Windows workstation with synthetic-only databases
1. Use the reviewed source branch/commit and configure a full **JDK 21** in `JAVA_HOME`, Maven on PATH, and a valid backend build environment (including applicable isolated integration-test prerequisites).
2. Use PowerShell at the repository root:

```powershell
$project = (Get-Location).Path
$commit = (git -C $project rev-parse HEAD).Trim()
& (Join-Path $project 'scripts\windows-runtime\Build-AroNexa-WIN11P-Portable.ps1') -ExpectedCommit $commit
```

3. After successful Maven verification and packaging, the output is under `Downloads\AroNexa-Clinic-Portable-Java21-<shortSHA>`.
4. In the authorized **test** process environment, provision `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, and `SPRING_DATASOURCE_PASSWORD` using the project's secure local runtime configuration. **Do not commit credentials or echo passwords into logs.** Other role/authentication configuration may be required by the current app contract.
5. Double-click `Start-AroNexa.cmd` on a Windows host whose application control permits the packaged Java runtime. It starts the **backend process only**. Close the console or use Ctrl+C to stop it.

## Troubleshooting: intentionally gated suites and diagnostic-only packaging

The backend includes special disposable PostgreSQL, Playwright and two-JVM operator acceptance suites that cannot be run by an ordinary `mvn clean verify` without their reviewed synthetic-only runner. In particular, PILOT-06 expects `ARONEXA_PILOT06_BROWSER=SYNTHETIC_ONLY` and its QA root, PILOT-07 recovery expects `ARONEXA_PILOT07_RECOVERY_ACK=DISPOSABLE_SYNTHETIC_RECOVERY_ONLY`, and PILOT-07 interactive expects `ARONEXA_PILOT07_ACK=DISPOSABLE_FICTIONAL_LAB_ONLY` plus a specially constrained run directory. PILOT-05 requires a phase A/B disposable database with unique run marker; merely setting env variables is **not** authorization to run it against an ordinary PostgreSQL instance.

**Default** builder mode remains `mvn clean verify` and stops on test failure. To investigate Java packaging independently, an owner/operator may explicitly invoke `-PackageOnlyUnverified`. This runs Maven packaging with tests skipped and labels the output `SKIPPED_FAILED_QA_UNVERIFIED`. Such artifacts are useful only for verifying JDK/jlink/launcher mechanics; they are **not** full QA passes, customer pilots, or release builds. Restore proper specialist QA using its approved isolated runner, and investigate premature PostgreSQL Testcontainers shutdown separately. Do not set acknowledgement values indiscriminately or reuse production databases.

```powershell
& (Join-Path $project 'scripts\windows-runtime\Build-AroNexa-WIN11P-Portable.ps1') -ExpectedCommit $commit -PackageOnlyUnverified
```

## Read-only portable preflight (before any database startup)

For an already-created portable folder, run the preflight separately. It checks the source manifest, non-release designation, JAR SHA-256, Spring Boot JAR layout, bundled Java 21 version, launcher DB configuration guards and localhost bind contract. **It will not start the backend, PostgreSQL, Docker or clinical APIs.**

```powershell
$worktree = Join-Path $env:USERPROFILE 'Application Development\AroNexa-WIN11P-Portable-QA'
$portable = 'C:\Users\ganes\Downloads\AroNexa-Clinic-Portable-Java21-55da40e0ca01'
& (Join-Path $worktree 'scripts\windows-runtime\Test-AroNexa-WIN11P-Preflight.ps1') -PortableDirectory $portable
```

This is not a replacement for the separately isolated database test or WIN-14 clinical/security acceptance. Never point the launcher to existing demo/pilot/customer PostgreSQL instances just to force startup. A future disposable DB runner must verify ephemeral provisioning, exact run-specific marker, runtime least-privilege roles, test-only migration scope, clean teardown, and distinct-process restart. The current application-dev profile expects separate migration owner, app, authentication and patient DB principals, so the three simple datasource variables in the launcher alone do not establish a valid secured runtime.

## Explicit exclusions / remaining WIN-11P + WIN-11B work
- This is a **bootstrap artifact**, **not** the finished integrated clinic application. There is no guarantee that this branch includes complete connected Reception UI, static React asset serving, appointment/queue clinical contract, or separate-process restart evidence.
- PostgreSQL 17 must already be provisioned separately; **no bundled database** or automated service/startup, encrypted backup/restore, signed offline licensing, recoverability, secure installer, or clinical approval is provided by this script.
- The Maven test gate alone is not WIN-14 acceptance. Independently verify authorized login, persistent patient search/registration, booking/check-in, queue, cross-tenant/branch denial, offline restart, secure backup/recovery and full clinical workflows before any customer installation.
- No real patient information in development QA. No deployment, merge, RLS/authorization weakening or production database modification.
- Smart App Control / WDAC can still block untrusted runtimes or launchers. Do not treat a CMD entrypoint as a policy bypass. Preserve signed native WIN-11 acceptance as a separate workstream.
- Java runtime distribution licenses and security updates must be reviewed before customer use.

## Development evidence
- Source file creation commits are **not evidence that this Windows script executed successfully**. A Windows syntax check, JDK/JAR launch smoke test, integration tests, restart persistence, and independent security review still need to be recorded against the **exact source commit**.
