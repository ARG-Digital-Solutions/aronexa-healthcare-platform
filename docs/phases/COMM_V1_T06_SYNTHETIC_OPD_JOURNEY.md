# COMM-V1-T06 — One fictional OPD journey, role-first preview

**Parent:** Development #82, commercial execution #72.
**Release classification:** INTERNAL_FOUNDATION / NOT_READY. DRAFT; NO MERGE; NO REAL PATIENT DATA.
**Master:** Engineering Contract V3 Sections 7–10 and 18–22; phases 01–03; UX-01, SEC-01/02, CLN-01, RX-01, FIN-01, RPT-01.
**Release priority:** Windows offline Clinic Edition first; Online SaaS developed in parallel from the shared domain.

## Implemented scope
An independent synthetic workflow contract, not a merge or copy of previously gated branches. One fictional patient and fixed encounter navigate seven simulated actions (eight stages):
1. Receptionist confirms duplicate review (never merges real identities).
2. Receptionist books a fictional appointment.
3. Receptionist checks in the fictional visit.
4. Assigned doctor records simulated consultation draft stage.
5. Assigned doctor reviews simulated Smart Rx draft stage (no medicine order).
6. Billing staff views simulated invoice draft stage (no invoice or money movement).
7. Billing staff views simulated daily report stage (no actual totals).

UI is role-first with clear next step, progress, role handoff, reset, and synthetic in-memory history. Patient and encounter context remains stable across steps. Changing the demo persona is not authentication. No actual clinical or financial data, networking, storage or access to original Windows PostgreSQL.

## How to run in an isolated development checkout
From frontend/ after npm ci:
- npm run dev, open http://127.0.0.1:5173/opd-journey-preview.html
- node --experimental-strip-types --test test/opd-journey-model.test.mjs
- npm run build (TypeScript + root app only, NOT standalone preview bundle)
- npx --no-install vite build --config vite.opd-journey.config.ts (standalone dist-opd-journey build)

**At first authoring:** dedicated Node model test and standalone Vite build results NOT RUN on exact GitHub head. PR Quality Gates status pending. Never infer test PASS from successful prior synthetic PR checks.
QA Central plan COMM_V1_T06 must be separately registered/reviewed, with exact source SHA, model tests, desktop/mobile browser acceptance, denied-role and network-guard checks; it is NOT YET RUN.

## Dependency and production-contract handoff
- Canonical patient, doctor, branch, appointment, encounter, audit and status identifiers must be server-owned; demo IDs are not persisted identities.
- Real path: P01 authorized registration/duplicate review + appointment check-in → P02 encounter draft → separate authorized doctor Rx draft/finalization/immutable amendments → P03 financial posting and reconciled daily reports.
- All real commands enforce session, tenant, branch, role, assigned doctor, patient object, module entitlement and PostgreSQL least-privileged RLS. Frontend role switching is not security. Use transactional idempotency, stale-version checks, event provenance and audit logging.
- Clinical safety: physician-controlled finalization, ink separate from structured medication; preserve drafts; signed PDF and legal/privacy review, no AI prescribing.
- Financial: integer paise, locked posted receipts, authorized refunds, append-only audit and correct clinic-local day boundary.
- UX: one shared visit context with minimal repeated entry, obvious statuses, accessible keyboard/mobile navigation, clear validation, clinic staff acceptance.
- Windows: first-run wizard, offline launcher, signed license, encrypted backup/restore, expiry-safe continuity. #74 remains separate. SaaS needs its own deployment/security gate. No offline-to-cloud sync in V1.

## Security limitations and binding holds
1. This is a staged demo, NOT an integrated real clinical application. No patient record, API, DB, authorization enforcement, medication decision, print, legal invoice or user login.
2. No modification/copy/merge of reception/appointments PR #36/#61, secure registration PR #38/#64/#67, consultation #75, smart Rx #78 or billing #79.
3. Real E2E QA and clinician/finance/NVDA UAT NOT RUN for this new T06 scope.
4. #45 independent security review remains outstanding. #31 original Windows V8→V9 migration W3-E/W4 HOLD remains. #63 routine manual merge conditions and special PR #36/#38 restrictions continue.
5. No customer release, source merge, migration, secrets, shared data or deployment is authorized.

## Master V3 Section 22 — 16-field handoff
1. Phase: 01–03 synthetic journey integration contract COMM-V1-T06.
2. Branch baseline: main SHA b4d9e1ea9b5c6fdc3d2c29b561eba7d919a40e6f.
3. Deliverables: seven-action/8-stage synthetic state machine and role-first preview.
4. DB/API: none.
5. UX/digital ink: responsive role-first handoff; ink not implemented here (separate #78).
6. Security: model-level demo denial only, production security NOT RUN.
7. Functional tests: model tests authored; execution pending.
8. Regression: PR Quality CI pending; full integrated E2E NOT RUN.
9. Clinical/financial/legal review: NOT RUN.
10. Deployment: browser prototype only.
11. Artifacts: isolated TS model, UI/CSS, HTML, Vite config, Node test and this report.
12. Risk: unauthenticated persona selector, missing persistence and real module integration.
13. Backup/restore: NOT RUN.
14. Customer UAT, accessibility/NVDA: NOT RUN.
15. Classification: INTERNAL_FOUNDATION / NOT_READY.
16. Disposition: READY FOR INDEPENDENT SYNTHETIC QA; NOT READY FOR CUSTOMER USE.

Next task after T06 QA: design the real secure P01/P02/P03 encounter/back-end contract using disposable database fixtures; prepare independent Windows offline installer/recovery prototype. Never attach live registration or the original Windows database until applicable owner/security GO.
