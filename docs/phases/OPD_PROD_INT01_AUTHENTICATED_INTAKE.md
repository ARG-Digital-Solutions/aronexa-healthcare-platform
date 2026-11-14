# PROD-INT-01 — Secure production-source OPD intake HTTP boundary

**Classification:** INTERNAL_FOUNDATION / NOT_READY for customer use.  
**Baseline:** accepted R1-PILOT-07 technical test-lab Draft #132, exact source `3249e59ecccacd9f74d62863cd6d4ead47a3bee1`.  
**Purpose:** begin converting the clinical OPD workflow from disposable HTTP proofs into carefully reviewed production-source modules without copying test-only controllers.

## Deliverable

A real `src/main/java` Spring MVC endpoint:

```
GET /api/v1/branches/{branchId}/opd/intake/{registrationId}
Authorization: Bearer <verified staff session>
```

A successful **explicitly entitled** branch returns an audited, no-store pseudonymous reference:

```json
{
  "tenantId": "<server-authorized tenant UUID>",
  "branchId": "<server-authorized branch UUID>",
  "registrationId": "<authorized existing registration UUID>",
  "patientId": "<patient UUID resolved by restricted SQL>",
  "status": "REGISTRATION_VERIFIED"
}
```

The request never accepts tenant or patient from the browser. The reused `OpdRegistrationHandoffService` requires a current authenticated session plus `APPOINTMENT_BOOK`, live OPD module entitlement, and then `PatientAccessService` independently demands `PATIENT_REGISTRATION_READ`, enabled registration module, the assigned branch and dedicated `aronexa_patient_runtime` PostgreSQL RLS. Successful read audit is written in the same patient SQL transaction. The service does not return internal session/membership IDs to the HTTP controller. Invalid/unavailable registrations fail closed with no reusable authorization capability.

The frontend adapter `productionOpdIntakeClient.ts` sends an in-memory bearer over same-origin GET (no browser credential storage), validates every returned UUID and exact patient/registration continuity, and suppresses raw error bodies, tokens and demographic data. **It does not issue a booking or assume that a successful GET grants permission for a later write.**

The isolated connected-Reception **test preview** now offers an optional **Verify OPD intake (read-only)** button after an authorized registration or recovery, visible only when the current staff session has both `APPOINTMENT_BOOK` and `PATIENT_REGISTRATION_READ`. It calls the **production-source** GET, shows a generic success/denial without retaining a token or authorizing the next booking, and clears the status on logout or switching registrations. This preview remains isolated from the production `App.tsx` dashboard, which still uses demonstration data.

## Production enablement and database impact

- All OPD/registration modules are **unreleased and disabled by default** by existing Flyway V7/V9. Production module activation and grants are NOT made by this PR.
- The protected GET handler is production-source code but returns access denial in an ordinary unprovisioned deployment. No permission is auto-granted.
- **NO new Flyway migration, original DB access, DDL/DML write API, registration POST, appointment POST, consultation, prescription or billing in production.** Existing candidate test controllers remain strictly in `src/test/java` with test-only profiles.
- No frontend customer dashboard switches to clinical mode; the current home dashboard remains a synthetic demonstration. A production client adapter is available to wire after authorized workflows are implemented.
- No signed Windows installer, restricted OPD writer activation, offline license, encrypted backup, financial reconciliation or clinician signoff is claimed.

## Exact-SHA local acceptance (Windows)

Use a **fresh detached worktree at the Draft PR's exact current head SHA** and no inherited Spring or datasource variables:

```powershell
powershell.exe -NoProfile -File .\scripts\pilot\Test-AroNexa-ProdInt01-IntakeHttp.ps1 -ExpectedCommit <EXACT_PR_HEAD_SHA>
if ($LASTEXITCODE -ne 0) { throw 'PROD-INT-01 acceptance failed' }
```

The runner performs `npm ci`, all frontend unit tests including forged-identity and revoked-session client denial, TypeScript plus connected preview build, and the focused `OpdIntakeProductionHttpAcceptanceTests` JUnit class. These are **actual HTTP** requests to `src/main/java` controller, plus V1-V9 migrations and a **dedicated nonowner patient role** in an automatically disposed PostgreSQL 17 container. Four focused JUnit scenarios:

1. Authorized caller: exact existing registration maps to server patient; response contains no name, membership/session or clinical details; read audited; appointment POST stays denied.
2. Wrong tenant, wrong branch, non-existent registration and immediately revoked branch assignment fail closed without reading/auditing another patient.
3. Missing `APPOINTMENT_BOOK`, missing `PATIENT_REGISTRATION_READ`, inactive OPD tenant state or inactive patient registration tenant state each deny independently.
4. Anonymous/malformed bearer, revoked session and `must_change_password` users cannot read.

The expected guarded success marker is:

`PRODINT01 PRODUCTION-SOURCE INTAKE HTTP / DISPOSABLE PG17 QA PASSED`

**No command has been run on the new branch yet.** If it fails, report the first specific compilation or JUnit assertion. A previous Pilot-07 PASS at a different SHA is not a new-SHA PASS.

## Production integration next after this slice

The critical path is **PROD-INT-02 restricted appointment booking/check-in**, built from existing production-source `OpdBookingCommand` and `OpdRestrictedSqlAdapter`. Before ever enabling its HTTP route in a real clinic:

- independently review `deploy/opd/review-only` proposed migrations, original V8/V9 hold #31, forward-only upgrade and rollback, slot uniqueness and immutable audit;
- prove an `aronexa_opd_runtime` **dedicated nonowner** SQL login with no direct table grants/BYPASSRLS, transaction-local proof and session revocation, tenant/branch/doctor/object scope, idempotent request and concurrent slot conflict;
- provision/rotate keys without hard-coded credentials, validate approved module/role activation, and enforce denial for forged/replayed/stale commands;
- provide real restart persistence, client re-read, negative role tests and a bounded Windows tester kit without turning on test-only endpoint profiles.

Then incrementally move Doctor consultation, prescription drafts with clinician-owned immutable revision, Billing and daily reports into reviewed production modules. **Do not copy the test-profile controller or migrator SQL into production.**

Customer release remains **NO-GO** pending original DB owner approval, independent security #45/#123/#125, clinical/accounting UAT, installed Windows service, signed offline license, authenticated encrypted nonempty DB restore and explicit customer-release GO.

## V3 mandatory 16-field report

1. **Phase:** PROD-INT-01 protected OPD intake preflight.
2. **Baseline:** Pilot-07 owner-reported technical PASS on its exact earlier SHA; main remains separately governed.
3. **Features:** real Spring GET registration→patient intake reference and frontend adapter.
4. **DB/API:** no migrations; existing V1–V9 restricted patient RLS read/audit.
5. **UX:** optional read-only intake verification button in isolated connected-Reception preview; customer dashboard unchanged.
6. **Security:** signed-in session, dual permission, OPD/registration module gates, branch/tenant and nonowner SQL, no-store response.
7. **Tests:** HTTP/PG17 four cases and frontend negative tests authored; NOT EXECUTED at current PR head.
8. **Regression:** Pilot06/07 prior exact-SHA PASSES are not a substitute for PROD-INT-01 QA.
9. **Compliance:** no prescriptions, real patient contacts, payment or clinical data processed in this slice.
10. **Deployment:** no deployment; localhost disposable PG17 acceptance only.
11. **Artifacts:** main controller, security rule, frontend client and tests, exact-SHA runner and doc.
12. **Remaining defects/gaps:** production writes are intentionally absent; module unreleased by default; security reviews outstanding.
13. **Backup/restore:** Pilot07 fictional nonempty same-container restore accepted; real encryption/second-device restore still pending.
14. **Customer UAT:** NOT RUN.
15. **Classification:** INTERNAL_FOUNDATION / NOT_READY.
16. **Handoff:** ready for local exact-head QA and independent security review; commercial NO-GO.
