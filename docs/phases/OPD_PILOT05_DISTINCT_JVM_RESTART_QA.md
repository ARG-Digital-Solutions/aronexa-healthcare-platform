# R1-PILOT-05 — Cross-JVM Restart / Same Disposable PG17

**Classification:** source-only, Windows PowerShell acceptance prototype. No code on this branch has been run on the owner's Windows PC yet. This is NOT a commercial installer or customer UAT.

## Exact purpose

The earlier R1-PILOT-04 proof opens a consultation after new HTTP logins inside one running JVM. R1-PILOT-05 adds two **separate Maven Surefire JVM executions** against a **single newly created, isolated** `postgres:17-alpine` container.

| Phase | Executing process | Required result |
|---|---|---|
| A | Java process A, started by first `mvn test` command | Fictional receptionist books/checks in; receptionist token revoked; assigned doctor signs in, saves a consultation draft; row committed and marker stores JVM PID |
| Process boundary | Phase-A Maven/Surefire completes before phase B is invoked | No Java context from A is shared with B; PostgreSQL container continues running |
| B | Java process B, started by a **new** `mvn test` command | Fresh doctor login recovers exact appointment, patient and draft revision, verifies prior PID differs; receptionist cannot read clinical draft; mark PASS_B |

### Safety and controls

- Runner `scripts/pilot/Test-AroNexa-Pilot05-DistinctJvm-Restart.ps1` requires exact clean Git SHA and rejects inherited datasource/profile variables.
- Docker creates **one new container** with `--rm`, generated name and label, **random loopback host port**; **no bind mounts or volumes**. It does not touch local/demo PostgreSQL on host port 5433.
- Runner explicitly bootstraps the **test-only roles**, creates an isolated `qa_guard.pilot05_run_marker` with unguessable run UUID, then sets only the test-specific child environment.
- `Pilot05DisposableDatabaseGuard` checks the **exact marker and database name before Spring/Flyway connects**; a JDBC URL alone is not enough. It builds datasource configuration using only fixed loopback host/port and the known disposable database name.
- Each JUnit phase uses the existing `appointment-candidate` test controllers and R1-04..R1-08 synthetic SQL; zero new production controllers or migrations.
- Only a container with the exact generated ID, name and label may be stopped by cleanup. If label inspection fails, cleanup refuses rather than stop any other container.
- Two Surefire XML reports are copied to `%USERPROFILE%\AroNexa-Local-QA\PILOT05-<run-uuid>\`, and are verified for one passed test each. The final marker must show phase `PASS_B` and two distinct PIDs before the runner prints PASS.
- Reported credentials and names are **fictional only**; never use real patient data.

## Windows acceptance

In a clean detached worktree at the *current R1-PILOT-05 Draft PR head SHA*, from repo root:

```powershell
powershell.exe -NoProfile -File .\scripts\pilot\Test-AroNexa-Pilot05-DistinctJvm-Restart.ps1 -ExpectedCommit <EXACT_SHA>
if ($LASTEXITCODE -ne 0) { throw "PILOT05 QA FAILED" }
```

Java 21, Maven, Docker Engine/Compose-capable Windows environment, with enough free memory for one Docker PostgreSQL container and the Maven test process, are required. Run with no inherited `SPRING_DATASOURCE_*` or external active profile variables. On failure, retain the first failure log and Surefire XML; do not rerun the full 323-test suite blindly.

## Boundaries of what PASS proves

A green run proves that a **test-only** backend HTTP workflow survives the termination and creation of distinct JVMs while keeping one *disposable* PostgreSQL database. It also proves authorized clinician recovery, refusal of a stale receptionist token, and receptionist draft denial for that fixture.

It does **not** prove Windows service restart, power interruption or installer, permanent database retention, encrypted backup/nonempty restore, production least-privilege SQL controls, revoked-membership concurrency correctness, actual browser interaction, independent accessibility/clinical review, or any real patient use. Those remain controlled R1-09 NO-GO gates, along with original database HOLD #31 and independent reviews #45/#123/#125.

**Release action:** no merge, deployment, original-database migration, change of policy or clinical authorization.
