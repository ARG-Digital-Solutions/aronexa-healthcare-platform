# PROD-INT-02 — Review-gated production-source appointment booking and check-in

**Classification:** INTERNAL_FOUNDATION / NOT READY FOR CUSTOMER USE  
**Baseline:** owner-accepted PROD-INT-01, Draft PR #133, exact source `1fcb763239099cd86fbcd9b232488a6d820eb85e`.  
**Release status:** NO-GO until original DB, restricted writer, security, licensing, recovery and clinician UAT approvals.

## Why this is the next delivery increment

Pilot-06 and Pilot-07 proved a fictional integrated Reception → Doctor → Rx Draft → Billing journey over test-profile HTTP. PROD-INT-01 established the first **production-source authenticated intake read**. Production booking/check-in was not wired; `OpdBookingCommand` and `OpdRestrictedSqlAdapter` were deliberately non-beans, with test-only adapters under `src/test/java`.

PROD-INT-02 introduces the smallest safe **production-source** HTTP commands built on those source-owned contracts:

```
POST /api/v1/branches/{branchId}/opd/appointments
POST /api/v1/branches/{branchId}/opd/appointments/{appointmentId}/check-in
```

Booking takes `registrationId`, `doctorMembershipId`, `visitDate`, `slotStart`, `requestId`. Check-in takes `expectedRevision` and `requestId`. The browser cannot choose `tenantId`, `patientId`, `sessionId`, proof key or SQL identity. The source-owned command re-reads the authorized registration→patient mapping and checks the active session, permission and OPD/registration module rules. The restricted PostgreSQL writer independently rechecks live session/tenant/branch/doctor/revocation, request idempotence, slot uniqueness, audit and revision in the same server-authorized write path.

**Important:** production activation is **FALSE by default**. `OpdBookingHttpController` is conditional on `aronexa.opd.booking.reviewed-activation=true`; the security filter independently denies both routes without that setting. Even when the setting is true, without a separately reviewed, explicitly provisioned `RestrictedVisitTransaction` bean, both commands return a no-store **503** rather than using an unprivileged/stale proof or silently falling back to a migrator. This branch provides **NO** general-purpose production writer bean. The ONLY writer wired here is a nested `@TestConfiguration` in a disposable Testcontainers test class, not part of the production JAR.

Do **not** set the activation flag in a clinic or normal developer profile to make the route work. That would bypass the engineering release sequence.

## HTTP semantics and privacy

- New booked appointment: **201**, idempotent replay: **200**; check-in: **200**. Receipts contain pseudonymous tenant/branch/patient/registration/doctor/appointment IDs, stage, revision and replay flag, but no login token, identity demographics, clinical note, SQL proof or patient contact.
- Unauthorized role, wrong tenant or wrong branch: **403**; missing/expired bearer: **401**. Invalid payload: **400**; stale revision / occupied doctor slot / rejected guarded SQL command: **409**; no independently provisioned restricted writer under a reviewed activation: **503**.
- Every response is `Cache-Control: no-store`. Errors use generic codes without echoing body or SQL internals. No file/database tokens or passwords are committed to source.
- The new `frontend/src/productionOpdBookingClient.ts` validates receipt continuity, forbids client-chosen patient/tenant, requires a caller-owned stable UUID `requestId` to safely retry uncertain bookings, does not store tokens or patient data, and refuses an unverified booking/check-in. This API client is **not activated** in the commercial UI; the existing test preview still points to test-only appointment candidate endpoints. There is no new customer UI claim.
- Do not use booking success as clinical consultation approval. Doctor consultation, final prescription and billing remain separate reviewed production increments.

## Security, migrations and governance

The `deploy/opd/review-only` PostgreSQL V11 candidate and `R110B2/R110B3B` privileged-definition routines are **rehearsal inputs only** in disposable PG17; they are not Flyway production V10/V11 or the original Windows V8/V9 DB. PROD-INT-02 introduces **no production migration, credentials, module activation, license issuer key, tenant/permission provisioning or original database operation**.

Before ever activating on a real Windows or hosted clinic installation:

1. Obtain forward-only migration and original DB hold #31 decisions. Independently review preflight, backup, transactional DDL and recoverability.
2. Provision a separately controlled, nonowner, non-BYPASSRLS `aronexa_opd_runtime` SQL account with no direct appointment table privileges, reviewed RLS/function owners, session locking, role/branch/doctor checks and append-only audit. Do not reuse test migrator credentials.
3. Independently review production signing/HMAC key custody, rotation, service account permissions and process secrets; no secrets in code/ZIP/frontends.
4. Prove adversarial forged patient/tenant/doctor/slot, concurrent booking, revoked session/role/branch and module-state races; full restart persistence, backups and alternative staff sessions.
5. Complete signed Windows installer + service ownership, secure offline license continuity, encrypted *nonempty* PostgreSQL restore to a second machine, clinical Rx verification, billing checks, keyboard/NVDA and explicit owner customer GO.

The prior owner approvals permit routine implementation and bounded disposable tests **without repeated approvals**, but do **not** authorize original DB changes, production activation, merges, independent review self-signoff or customer data.

## Windows exact-SHA acceptance

From a fresh detached clean worktree at the *current PR head*, not Pilot-07/PROD-INT-01:

```powershell
powershell.exe -NoProfile -File .\scripts\pilot\Test-AroNexa-ProdInt02-RestrictedBooking.ps1 -ExpectedCommit <EXACT_HEAD_SHA>
if ($LASTEXITCODE -ne 0) { throw 'PROD-INT-02 acceptance FAILED' }
```

The runner verifies exact SHA/clean tracked tree, rejects inherited JDBC/profile/key/activation environment values, needs Java 21, Maven, Docker and npm; runs frontend test and TypeScript/Vite build; executes both JUnit classes over **Testcontainers PG17**:

- `OpdRestrictedBookingPostgresTests`: old restricted-credential, transaction and revocation regression suite **plus** actual **production-source** HTTP booking/check-in from real bearer with a disposable-only `RestrictedVisitTransaction` bean; 201/200 idempotent replay, 200 check-in, 409 stale/slot collision, 401 revoked session, 403 cross-branch/registration and revoked permission; records verified via independent SQL.
- `OpdIntakeProductionHttpAcceptanceTests`: prior accepted production read HTTP/regression suite **plus** new regression proving production booking/check-in routes remain 403 when reviewed activation is absent (even for a valid staff session with modules provisioned in disposable fixture).

The runner checks both Surefire reports have **zero failures/errors/skips** and that all new mandatory test case names exist, then builds the Spring Boot JAR and checks that the main OPD controller is packaged but the test-only controllers are not. Only then prints:

`PRODINT02 REAL PRODUCTION-SOURCE HTTP / RESTRICTED PG17 QA PASSED`

**Authored, NOT EXECUTED on owner's Windows machine.** Source-review confidence and the previously accepted PROD-INT-01/Pilot-07 SHA cannot substitute for fresh QA. If it fails, fix the isolated branch and retest the new exact SHA.

## Follow-on: PROD-INT-03

Once PROD-INT-02 passes local security regression, prioritize production Doctor consultation revision with assigned-clinician and object-level authorization, followed by structured prescription draft with immutable versioning, then billing and daily reports. The customer workflow remains incomplete until all components run behind authorized production infrastructure and are accessible through a simple role-based Windows UI. A permanently installed clinic application is a **separate milestone**.

## Master V3 mandatory 16-field handoff

1. **Phase:** PROD-INT-02 production-source OPD book and check-in.
2. **Sources:** PROD-INT-01 accepted exact SHA; reviewed-design `OpdBookingCommand`/`OpdRestrictedSqlAdapter`; test-only SQL candidates retained.
3. **Features:** gated source-owned HTTP book/check-in and small production client adapter.
4. **DB/API:** 2 new conditional POST routes; no new schema/migration/runtime datasource/credentials.
5. **UI/UX:** reusable client logic only; no customer UI/role nav change.
6. **Security:** default-off security + component gate, active bearer, distinct SQL writer, tenant/branch/doctor/slot/revision and audit.
7. **Tests:** source-authored synthetic real HTTP/PG17, security negatives, frontend; NOT RUN on new SHA.
8. **Regression:** both existing intake and restricted SQL suites configured, not yet executed on new source.
9. **Compliance:** no real clinical Rx, pharmacy, payment or patient contact data.
10. **Deployment:** none; isolated local Testcontainers only.
11. **Artifacts:** source controller/client, test configurations, QA runner and documentation.
12. **Open defects:** production SQL writer not deployed; migrations, key custody and clinical workflow pending.
13. **Recovery:** Pilot-07 fictional same-container dump/restore accepted; commercial encrypted/second-device restore NOT RUN.
14. **Customer UAT:** NOT RUN.
15. **Classification:** INTERNAL_FOUNDATION / NOT READY.
16. **Handoff:** READY FOR EXACT-SHA LOCAL QA, not permitted for clinic deployment.

## Windows prerequisite STOP — first owner attempt (2026-11-14)

Owner's initial exact-SHA Windows runner attempt at `0395a9069bbf982f98dc2d146bd60de2c4516007` **STOPPED before test execution** with a combined message: `PRODINT02 STOP: Docker, Java 21/JDK jar, Maven, npm required.` This message did not identify which command was absent, and must not be recorded as an HTTP/PG17 test failure or a PASS.

Focused runner-only correction: `jar.exe` was used solely to list files inside the built Spring Boot JAR; replace its external CLI dependency with read-only `System.IO.Compression.ZipArchive` and keep the identical checks for presence of production-source `OpdBookingHttpController.class` and absence of test-only controllers. The preflight now lists exactly which of `docker.exe`, `mvn.cmd`, `npm.cmd`, `java.exe` are missing from PATH. Java 21 and a compiler-capable JDK are still needed to build source, Maven/Docker/npm remain prerequisites; the updated runner changes neither RLS nor application behavior.

**This code has NOT been run on the owner's Windows machine at the corrected SHA.** Recheck the exact Draft PR #134 head and use a NEW clean worktree for retesting; do not manually bypass exact SHA, datasource/profile guards, restricted SQL or customer-release NO-GO rules. If it still stops at prerequisites, use its new specific missing-tool message to repair the Windows PATH/install before retrying.
