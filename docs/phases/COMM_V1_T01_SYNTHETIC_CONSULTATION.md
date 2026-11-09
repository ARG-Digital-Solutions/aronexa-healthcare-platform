# COMM-V1-T01 — Synthetic Doctor Consultation Workspace

**Parent:** Development #72 / #73  
**Status:** INTERNAL_FOUNDATION / NOT_READY — draft only, no commercial release.
**Base:** main at `b4d9e1ea9b5c6fdc3d2c29b561eba7d919a40e6f`.  
**Master:** Engineering Contract V3.0 Sections 7–10, 18–22; UX-01, CLN-01, SEC-01, SEC-02, proposed EMR-360-01.

## Goal and demo

One simple, role-specific OPD doctor workspace demonstrating fictional patient selection, allergies/previous visit summary, complaint/history/vitals/assessment/plan/follow-up note entry, clear statuses, and in-memory draft version increments. Option A + Option D design colors and responsive layout. No Rx or clinical finalization in this increment.

To run from `frontend/` after `npm ci`:
- `npm run dev` then open `http://127.0.0.1:5173/consultation-preview.html`.
- `npx vite build --config vite.consultation.config.ts` creates **only** the consultation preview bundle separately in `dist-consultation/` (internal demo, not distribution).
- `node --experimental-strip-types --test test/consultation-model.test.mjs` runs the 14 local Node model checks, with no external test data.

This is deliberately a **standalone preview**; the existing `App.tsx`, styles, registration and appointment branches are untouched. The standard root index remains the original foundation scaffold.

## Verified narrow evidence (2026-11-10 IST)

- LOCAL: Node v22.16.0, isolated `node --experimental-strip-types --test`: **14 passed, 0 failed, 0 skipped**; covers clinician vs receptionist, other doctor, tenant and branch denial, unknown patient, empty/oversized field, invalid save time, immutable version increment and mismatched patient/author.
- UI Vite build: **NOT RUN LOCALLY** at the point of authoring; exact PR head GitHub Actions must determine application build status.
- Independent browser / screen-reader accessibility and doctor UAT: **NOT RUN**.
- API, backend auth, PostgreSQL RLS, integration, security penetration assessment, Windows package and original database: **NOT RUN / NOT IN SCOPE**.
- No patient identifiable information, credentials, external AI, transport or persistent storage exists in this implementation.

## Security boundaries

The `role` select and authorization checks are **only UI/model demonstrations**, not a production security mechanism. Future real APIs must independently enforce signed-in user, tenant/branch/doctor/object/module ownership, revocation and PostgreSQL least-privilege RLS. Never promote this preview into a clinical route merely because frontend checks pass. Synthetic draft content exists in React memory and disappears on refresh, without authentication, database, consent, data lifecycle or clinical sign-off.

## UX observations and next acceptance

- Reception/doctor role switching produces an explicit restricted view; inspect keyboard and focus order, label associations, readable validation, narrow/mobile layout and no patient-crossing on change.
- In the real encounter flow, prevent losing unsaved drafts on patient change; current synthetic preview **discards unsaved changes with notice** and must not be reused unchanged in production.
- Clinician to validate clinical fields and practical click count, including stylus-enabled clinical forms (Phase 02 mandatory) and structured medication as later bounded tasks.
- Future authorized work must integrate through explicit stable interface contracts; avoid overlapping `frontend/src/App.tsx` with PRs #36/#61; no live registration coupling before independent #45 security clearance.

## 16-part phase closure snapshot (V3 Section 22)

1. Phase 02 / COMM-V1-T01; 2. Inspected main scaffold; 3. Standalone synthetic workspace; 4. No DB/API change; 5. Responsive Option A/D UI, ink not implemented; 6. All real security/clinical acceptance outstanding; 7. Node 14/14 LOCAL; 8. Cross-module regression NOT RUN; 9. Regulatory/privacy release review NOT RUN; 10. No customer deployment mode; 11. Draft source only; 12. Unsaved draft loss on switch, auth-only demo, no finalization; 13. No backup/restore in scope; 14. Customer UAT NOT RUN; 15. INTERNAL_FOUNDATION / NOT_READY; 16. **READY FOR ISOLATED REVIEW, NOT READY FOR CUSTOMER RELEASE**.

## Governance

Draft PR only; no merge or `main` change authorized. Issue #31 original Windows migration HOLD, #45 registration security gate, PR #36/#38 specific review obligations and #63 manual routine safeguards are unaffected. GitHub exact-head CI results must be reviewed before considering any later owner-authorized merge.
