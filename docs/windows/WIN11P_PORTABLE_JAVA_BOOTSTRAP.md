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

## Explicit exclusions / remaining WIN-11P + WIN-11B work
- This is a **bootstrap artifact**, **not** the finished integrated clinic application. There is no guarantee that this branch includes complete connected Reception UI, static React asset serving, appointment/queue clinical contract, or separate-process restart evidence.
- PostgreSQL 17 must already be provisioned separately; **no bundled database** or automated service/startup, encrypted backup/restore, signed offline licensing, recoverability, secure installer, or clinical approval is provided by this script.
- The Maven test gate alone is not WIN-14 acceptance. Independently verify authorized login, persistent patient search/registration, booking/check-in, queue, cross-tenant/branch denial, offline restart, secure backup/recovery and full clinical workflows before any customer installation.
- No real patient information in development QA. No deployment, merge, RLS/authorization weakening or production database modification.
- Smart App Control / WDAC can still block untrusted runtimes or launchers. Do not treat a CMD entrypoint as a policy bypass. Preserve signed native WIN-11 acceptance as a separate workstream.
- Java runtime distribution licenses and security updates must be reviewed before customer use.

## Development evidence
- Source file creation commits are **not evidence that this Windows script executed successfully**. A Windows syntax check, JDK/JAR launch smoke test, integration tests, restart persistence, and independent security review still need to be recorded against the **exact source commit**.
