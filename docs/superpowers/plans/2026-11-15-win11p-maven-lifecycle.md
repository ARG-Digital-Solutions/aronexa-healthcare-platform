# WIN-11P Maven lifecycle correction plan

Spec: owner authorization in the current issue #146 task (2026-11-15). Existing approved product worktree/PR #148 only. No Central QA execution under the stale PR #37 plan.

## Global constraints
- Preserve every discovered backend test method and original assertions/security guards.
- The default unfiltered Maven run remains fail-closed. Only the matrix coordinator may partition tests, and it must aggregate every lane before claiming completion.
- Method-level tags map gated tests to one explicit lane. Untagged tests belong to the ordinary lane.
- Each acknowledgement is copied only into its authorized child process. No global or machine-level environment changes.
- Fresh loopback-only marked PostgreSQL 17 containers only; never inspect or connect to original databases.
- Do not bind/contact/stop port 5176 while the owner QA process is active. RC03 may use previously approved 5186.
- Local execution is developer evidence only. Central QA requires a refreshed independently reviewed plan and remains NOT_RUN.
- Preserve pre-existing untracked logs, generated frontend build output, all worktrees, draft PR state and rule 27 hold.

## File map
- backend/pom.xml: opt-in Surefire profiles for discovery, ordinary, and single tagged method lanes; unique report directories.
- backend/src/test/java/com/argdigital/aronexa/appointment/Pilot05SeparateJvmHttpTests.java: method tags for legacy and RC03 phases plus the separately guarded Pilot09 operator lab.
- backend/src/test/java/com/argdigital/aronexa/appointment/AppointmentCandidateHttpAcceptanceTests.java: tags for Pilot06 browser, Pilot07 recovery, and Pilot07 operator lab.
- backend/src/test/java/com/argdigital/aronexa/appointment/Win11pTestInventoryTests.java: JUnit method inventory discovery, lane mapping validation, sanitized inventory artifact.
- scripts/pilot/Win11p-MavenMatrix.psm1: matrix definition, report parsing and exact inventory/result reconciliation.
- scripts/pilot/Test-Win11p-MavenMatrix.ps1: dependency-free focused regression tests for coordinator reconciliation.
- scripts/pilot/Invoke-Win11p-MavenMatrix.ps1: SHA-pinned coordinator, process-scoped authorizations, ordered execution, NOT_RUN/FAIL/PASS aggregation.
- scripts/pilot/Test-AroNexa-Pilot05-DistinctJvm-Restart.ps1: retain guarded PG17 setup and separate JVMs while passing environment only to each Maven child and writing per-phase reports.
- scripts/pilot/win11p-maven-matrix.json: explicit test-method-to-lane inventory and required result policy.
- docs/windows/WIN11P_MAVEN_TEST_LIFECYCLE_BUILD_LOG_20261115.md: exact commands, outcomes, changed paths, QA impact, open operator gates and source SHA.

## Tasks
1. Write focused coordinator tests for complete unique PASS, missing, duplicate, skipped, failed, errored, invalid-tag and non-pass NOT_RUN cases. Run and verify the intended RED result before implementation.
2. Add method-level JUnit tags and explicit Surefire matrix profiles. Prove discovery, ordinary exclusion, and single-method tagged selection with focused Maven invocations.
3. Implement inventory discovery and deterministic matrix/reporter. Validate SHA and clean tracked checkout; keep default Maven unfiltered; run every executable lane in a fresh child with an allowlisted environment; collect per-lane reports and reconcile all discovered method IDs exactly once.
4. Refactor Pilot05 invocation to set disposable marker variables only in the corresponding child process; preserve same marked DB and distinct A/B JVMs; preserve RC03 5176 default/5186 approved option.
5. Run focused coordinator tests, then on a temporary exact-source clone run inventory discovery and the ordinary backend suite. Run Pilot05 A/B only when permitted by the updated source plan. Do not start Pilot06/Pilot07/Pilot09 operator lanes without genuine acknowledgements and their required isolated environment.
6. Record exact tested SHA/counts/statuses, update PR #148 draft evidence and QA issue #38 with changed-path scope and stale-plan blocker. Do not activate or execute Central plan.

## Acceptance checks
- Discovery list and lane configuration reconcile exactly; duplicate, missing, failed, errored or skipped mandatory methods fail the aggregate.
- mvn verify without matrix selection still executes all discovered tests and refuses missing acknowledgements/markers.
- Ordinary profile runs all untagged tests and no guarded methods.
- Guarded profiles select only the declared method and require its tag.
- Matrix never returns PASS if any required lane is NOT_RUN, BLOCKED or failed.
- Focused coordinator tests and ordinary suite outcomes are recorded from actual runs.
- Pilot06/07/Pilot09 lanes remain NOT_RUN without real operator evidence; port 5176 and external databases remain untouched.
- QA Central PR #37 plan pins and path allowlist are not treated as current authorization; no Central PASS is claimed.

