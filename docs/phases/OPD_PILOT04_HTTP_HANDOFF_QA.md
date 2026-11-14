# R1-PILOT-04 — Real HTTP Reception / Doctor Handoff QA (test-only)

This milestone strengthens **acceptance evidence**, not customer-runtime activation.

## Basis

- Stack atop Draft PR #128's `26d2bd57f2826741cccb81421eac17dd19079f9d` owner-reported frontend unit/build acceptance.
- The existing `AppointmentCandidateHttpAcceptanceTests` runs a random-port loopback Spring Boot server, uses a disposable Testcontainers PostgreSQL 17 database, creates fictional users and performs actual bearer-authenticated HTTP calls.
- Existing methods already test appointment persistence, receptionist check-in, assigned-doctor draft save and independent login. The R1-PILOT-04 case exercises the **same end-to-end reference and explicit role transition** as a single fail-closed scenario.

## One-command acceptance

1. Check out a **clean detached worktree** at this Draft PR's exact new head SHA, not at the prior PR #128 SHA.
2. From its repository root, with Maven, Java 21, Node/npm and Docker Engine available, run:

```powershell
powershell.exe -NoProfile -File .\scripts\pilot\Test-AroNexa-Pilot04-RealHttp-Handoff.ps1 -ExpectedCommit <EXACT_SHA>
```

The script refuses wrong SHA, tracked modifications and inherited `SPRING_DATASOURCE_*` values; checks Docker; runs **one selected JUnit HTTP test** against automatically disposable PG17; then runs `npm ci`, frontend tests and reception/doctor preview builds. It prints PASS only after exit-code guards complete. It never creates a persistent clinic database, changes Flyway production migrations or installs any service.

## Evidence classes

| Check | Included | Still absent |
|---|---|---|
| Authenticated loopback booking + check-in | YES, via existing helper and new real HTTP method | Production controller and SQL role approval |
| Reception logout before doctor reads | YES; revoked token gets HTTP 401 | Browser keyboard/UI interaction proof |
| Assigned doctor / correct patient context | YES; fresh login & `consultation-context` / draft HTTP identity equality | Clinician UAT |
| Foreign tenant doctor denied | YES, HTTP 403 | Complete concurrent same-tenant administrative revocation proof |
| Draft saved and recovered | YES, **new bearer login in same Spring Boot JVM** | **Separate backend JVM stop/start, independent process and installed Windows recovery** |
| Fictional frontend build/unit | YES, when script passes | Selenium/Playwright browser QA and connected flow live with seeded fixture |

**Important:** The JUnit `RANDOM_PORT` server runs only during its test lifecycle. Its fixture identities and automatically disposable database disappear after the test. The connected Vite preview cannot be opened later with those test credentials to claim real browser integration. Browser E2E needs a separately managed ephemeral server + fixture; do not point previews at production or the owner's healthy demo database.

## Next acceptance milestone (not implemented)

A separate-process persistence test must use one and the **same isolated PG17 test database** across backend process A (fictional registration, booking/check-in, draft write) and backend process B (restart, new session, same authorized read-back), with strong process IDs and evidence, and automatic cleanup; must reject any non-disposable database. Independent browser QA and doctor/reception role validation must then run against the same isolated fixture, not mocked routes.

R1-09 nine NO-GO release gates, Windows original DB HOLD #31, independent review #45, concurrent revocation #123/#125, signed installer/licensing/encrypted real DB backup and owner approval remain intact. No merges, deployments or clinical/patient use from this proof.
