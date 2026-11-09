# COMM-V1-T03 — Smart Prescription + Clinical Ink synthetic preview
**Parent:** Development #72 / #76
**Release classification:** INTERNAL_FOUNDATION / NOT_READY. **No live prescribing or clinical finalization.**
**Baseline:** 2026-11-10, Windows-first commercial strategy (Online SaaS developed in parallel).
**Master:** Engineering Contract V3.0 Sections 7–10, 18–22; Phase 02 RX-01/RX-02/RX-03/FORM-01/UX-01/SEC-01.

## Scope
- Separate, fictional clinician-first Smart Rx draft screen with typed medicine name, strength, form, dose, route, frequency, duration, quantity and instructions. Built-in catalogue contains **fictional placeholder names**, not medical recommendations.
- Medicine lines are explicitly clinician-entered; pure TypeScript model checks required fields, bounded content/quantity, immutable revisions, duplicate IDs and role/tenant/branch/doctor context.
- Mouse/stylus/touch ink canvas with undo/redo/clear and keyboard-accessible free-text fallback. Ink remains independent of structured medicine data.
- Draft preview **always watermarked invalid as prescription**. No finalize, issue, print clinical Rx, PDF, legal signature, backend API, DB, real patient records, network, storage, AI or live clinical authorization.
- Standalone entrypoint; original frontend App, Phase 01 branches and consultation PR #75 untouched.

## Test and local demonstration
From frontend after npm ci:
- Open http://127.0.0.1:5173/smart-rx-preview.html during npm run dev.
- Command: node --experimental-strip-types --test test/smart-rx-model.test.mjs
- Command: npx vite build --config vite.smart-rx.config.ts (separate preview bundle, **not** part of standard PR quality workflow).
- Standard npm run build compiles all TypeScript but bundles the original main app.
Test attribution: local Node 22.16.0 model checks **23/23 PASS** on the corresponding isolated implementation before pushing; verify exact PR head using CI and rerun the checked-out PR test command before merge. GitHub workflow/QA and separate preview build results must be recorded independently, not assumed.
**Not run:** real backend/API/PostgreSQL security, in-clinic accessibility/NVDA, clinician medication safety evaluation, PDF/ink sign-off, production packaging and customer acceptance.

## Safety and usability follow-ups
- Permission checks here are **demonstrative UI/model guards**, not production access control. Actual tenant/branch/doctor/patient/module/role authorization, audit and least-privileged PostgreSQL RLS are required before integration.
- All doses and orders require clinical validation and authorized explicit finalization. This preview does not perform allergy interaction checks, duplicate therapeutic class checks, dosing limits, tamper-proof finalization, or controlled amendments.
- Ink persistence, eraser, zoom, pressure, palm rejection, stroke attribution, recovery, PDF and finalized versioning require separately tested Phase 02 implementation. Mouse/touch/pen drawing is a limited first subset, not full FORM-01 completion.
- On real data, navigation must prevent draft loss and support keyboard/assistive technology. This preview intentionally loses all state on refresh, with visible disclaimer.
- Do not connect to PR #38 API or enable live registration while #45 is open.
- No original Windows PostgreSQL migration; #31 W3-E/W4 HOLD. #63 manual merge checklist applies; #36/#38 requirements unchanged.

## Master Contract Section 22 — 16-field report
1. Phase 02 task COMM-V1-T03.
2. Inspected scaffold and draft PR #75; no reused live clinical API.
3. Structured fictional Rx, reversible ink, draft preview.
4. No DB/API change.
5. Responsive Option A+D UI; initial pen/touch/mouse only, full ink engine NOT READY.
6. Frontend guard demo only; production security/clinical signoffs NOT RUN.
7. Local model checks 23/23 PASS; exact-head CI separately.
8. Cross-module browser regression NOT RUN; standard CI to follow.
9. Compliance/clinical prescription review NOT RUN.
10. Standalone internal browser preview only; no commercial Windows/SaaS mode.
11. Draft code, separate build entry and instructions.
12. Missing persistence, full digital ink, UAT, immutable finalization, allergy safety, real authorization.
13. No backup/restore in scope.
14. Customer UAT NOT RUN.
15. INTERNAL_FOUNDATION / NOT_READY.
16. READY FOR ISOLATED REVIEW ONLY; NOT READY FOR COMMERCIAL RELEASE.
