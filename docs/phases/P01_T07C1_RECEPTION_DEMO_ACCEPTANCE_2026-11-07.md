# P01-T07-C1 — Reception and patient registration synthetic demonstrator
Date: 2026-11-07 IST | Authorization: [Issue #35](https://github.com/ARG-Digital-Solutions/aronexa-healthcare-platform/issues/35)

## Classification
**INTERNAL DEMONSTRATION; NOT_READY for real patients/customer use.** No backend registration route, persisted MRN, database writes, patient authentication, linked identity or appointments exist in this task.

## Scope and design
- A simple Reception Overview (totals, sample arrivals/queue, quick actions), Patient List search, Registration form, explicit duplicate review and confirmation.
- Design system: #102D54 navy, #2563EB blue, #0D9488 teal, #F5F8FC light blue-gray, white surfaces. Keyboard focus and responsive layouts included.
- Initial data are fabricated fixture entries; new entries use React state only. On browser refresh all new entries disappear.
- Demo IDs `DEMO-XXXX` are **not MRNs**; sample branch and staff labels are not authenticated.
- Synthetic phone numbers only; never enter genuine patient details. Matching is exact normalized name with optional known-DOB match. It is not safe production duplicate detection; never infer identity from phone.
- Explicit modal confirmation is required before every new in-memory registration; no auto-merge.

## Changed-file boundary
Expected: `frontend/src/App.tsx`, `frontend/src/styles.css`, `frontend/src/receptionModel.ts`, `frontend/test/reception-demo.test.mjs`, `frontend/package.json`, this document. **No Java, auth security matcher, migration, SQL runtime, Docker, production config or original DB changes.**

## Run (isolated frontend only)
From a **fresh checkout of the feature branch**, PowerShell:
```powershell
cd frontend
npm ci
npm run build
npm run dev
```
Open the local Vite URL displayed by the CLI. **Do not start backend/Compose/Flyway** for this demo; especially do not connect the latest V9 backend to the original Windows V8 PostgreSQL instance.

## Test commands and gates
CI frontend `npm run build` performs `tsc --noEmit`, Vite production build and `npm run test:reception` (`node --experimental-strip-types --test test/reception-demo.test.mjs`) using Node 22.
- C1-A01: npm ci and frontend build PASS required.
- C1-A02: all synthetic model acceptance cases PASS required.
- C1-A03: invalid names, malformed phone, future/inconsistent DOB rejected, unknown DOB allowed.
- C1-A04: exact synthetic name triggers review; shared phone not an identity match; no auto-link or merge.
- C1-A05: explicit confirmation results in a fresh in-memory record and queue update.
- C1-A06: manual accessibility/mobile review required (narrow viewport, keyboard, screen reader, validation error focus).
- C1-A07: PR diff must include NO backend/SQL/runtime/security changes.
- C1-A08: owner verifies in-browser demonstration and approves any further stage separately.
- C1-A09: PR #32 stays draft; issue #31 W3-E NO-GO, W4 original Apply blocked.

**Execution evidence:** Tests/CI must be linked and reviewed after the PR executes; this plan is not proof of a PASS. Manual visual/keyboard acceptance remains NOT RUN unless explicitly recorded.

## Required follow-up real implementation task (NOT AUTHORIZED by this demo)
P01-T07-C2: implement protected server-backed registration using dedicated non-owner patient DB role and reviewed *additive* schema on a **fresh synthetic PostgreSQL only**, tenant/branch/role/RLS and module gates, idempotent MRN/registration transaction, safe duplicate candidates, secure contact/audit, negative/concurrency Testcontainers. No customer or original V8 DB touch. Later C3: integrate UI with real authorized API, perform Playwright and Windows acceptance, review authorization/privacy/security and explicit owner release gate.

## Governance
Issue #35 is the bounded authorization record. PR must remain **DRAFT / UNMERGED** until explicit owner merge authorization; any CI success is not permission to merge. No real PHI, clinical care or production release.
