# AroNexa Clinic Edition — Fictional OPD Preview Kit (R1-PILOT-01)

**Purpose:** give the owner a repeatable, testable set of existing UI builds while the commercial Windows pilot and security authorization remain **NO-GO**.

**Scope:** source-only, Windows PowerShell 5.1, React/Vite preview builds. No installer, PostgreSQL migration, production service, patient data, clinical orders, license signing, or backup.

## Exact source baseline

This work starts from owner-reported PASS of security/test candidate commit `1a53b303e8119cea8095a73ffbde092c9805d405`: 323 backend tests, 0 failures/errors/skips; frontend/reception/doctor/billing builds PASS at that **earlier SHA**. New preview-kit commits require their own local verification. Issue #124 is the Windows pilot track; #123 and Draft PR #125 remain the security track.

## Windows use (synthetic data only)

From a clean detached worktree at this preview kit's **current exact head SHA**:

```powershell
powershell.exe -NoProfile -File .\scripts\pilot\Build-AroNexa-Opd-Fictional-Previews.ps1 -ExpectedCommit <EXACT_HEAD_SHA>
```

The script checks clean tracked source and SHA, runs `npm ci`, then invokes the **existing** three connected preview builds; it verifies their HTML outputs. It does not run Java or contact any database.

To view a preview, open a new terminal in `frontend` and choose one:

```powershell
npm run dev:connected:test
npm run dev:connected:doctor:test
npm run dev:connected:billing:test
```

Vite serves on loopback at ports **5176**, **5177**, **5178** respectively. The configs proxy `/api/v1` to loopback port 8080; a running approved backend is a separate prerequisite for genuine API behavior. These are NOT interchangeable with a verified persistent cross-module OPD runtime. Do not point them at clinical or shared databases.

## Screen-by-screen inspection sequence (do not claim end-to-end integration)

| View | Entry | Expected bounded demonstration | Not proven |
|---|---|---|---|
| Reception | `connected-reception-preview.html` | Registration/search UI and connected-preview behavior | Production appointment booking, real tenancy/security on a clinic install |
| Doctor | `connected-doctor-preview.html` | Consultation and prescription draft preview | Signed/final prescription, real appointment-to-doctor handoff |
| Billing | `connected-billing-preview.html` | Fictional basic bill and collection UI preview | Authorized persisted invoices, payments, real day close |

Do not manually copy patient identity values from one screen into another and describe that as integrated state. `frontend/src/opdJourney.ts` implements UI receipt-linking structural checks, **not** backend authorization or persistence.

## Hard acceptance criteria for the next product increment

- One actual receptionist login and patient registration/search in a running application; no test-only controller in production.
- Actual appointment booking/check-in persisted across **separate** backend JVM termination/restart and retrieved in one authorized patient journey.
- Same linked patient/appointment transferred to doctor consultation and Smart Rx draft, preserving tenant/branch/doctor and revisions; finalized Rx remains deferred.
- Same linked encounter reaches basic bill and daily report, without fictional cash accounting misrepresented as actual transactions.
- All transitions return backend-authorized receipts; reject mismatched tenant/branch/patient and re-fetch on reload. No unsafe local cache of clinical data.
- Separate installed Windows service, license and **nonempty encrypted PostgreSQL backup/restore** rehearsal on isolated fictional records.
- Independent SQL/authorization/security review, DB migration HOLD resolution, clinician UAT, browser QA and explicit owner release GO.

**Classification:** developer-only preview kit, **not** the above completed product increment. The R1-09 inspector's nine NO-GO gates remain authoritative. Do not merge or deploy based on a green preview build.
