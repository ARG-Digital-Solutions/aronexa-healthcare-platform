# COMM-V1-T04 — Synthetic OPD Billing + Daily Reports
**Parent:** Development #72 / #77. **Classification: INTERNAL_FOUNDATION / NOT_READY**. Windows-first Clinic Edition, Online SaaS developed in parallel. Master Contract V3 §7, 8, 12, 18–22; Phase 03 FIN-01 / RPT-01 / UX-01 / SEC-01–03.

## Bounded implementation
- Independent browser demonstration of a single fictional OPD patient, fee entry in rupees, synthetic invoice, partial payments, per-payment refunds, and a daily collections report.
- Immutable in-memory event snapshots in integer paise. Reject fractions of a paise, negative/overpayment, excessive refunds, missing source payments, duplicate IDs, unsafe dates, unsupported roles, tenant or branch context mixing. No monetary calculations based on floating-point rupees.
- Financial daily report groups invoice amounts by invoice date, payments/refunds by event date and reconciles net receipts.
- Role-specific simple UI, navy/blue/teal design, responsive grid and input labels. No existing App or Phase 01 / Phase 02 files modified.
- **No** real patient data, money movement, payment provider, statutory tax invoice or receipt, persistence, refund settlement, database, API, financial authorization or customer release.
- Synthetic illustration of role checks is not production access control; real server authorization, RLS, audit, double-entry/ledger controls as required and reconciliation must be reviewed separately.

## Test instructions and status
- From frontend, after npm ci: npm run dev; open http://127.0.0.1:5173/billing-preview.html
- Run pure model suite: node --experimental-strip-types --test test/billing-model.test.mjs
- Build separate preview: npx vite build --config vite.billing.config.ts.
- Standard CI npm run build typechecks all frontend source, but only bundles root app and **does not** execute this Node test suite or separate preview build.
- At authoring: Billing model tests **NOT RUN** in CI; actual test output pending. GitHub PR Quality Gates to be reviewed at exact head; never count a successful build as finance test completion.
- Financial/clinical/receptionist acceptance, keyboard/NVDA, real SQL tests, pen tests and Windows installer acceptance: NOT RUN.

## Next integration
- Stable patient, encounter, doctor, fee catalogue and clinic branch contracts required before any server implementation.
- Prioritize workflow usability (minimum clicks and clear outstanding amount). Real bill must support authorized adjustments, immutable receipt numbering, payment allocation, refunds, audit, concurrency and recovery.
- Patient billing must remain separate from future SaaS subscription billing.
- No original Windows DB mutation (issue #31 W3-E/W4 HOLD); #45 registration gate, #36/#38 security/review, and #63 manual merge process unchanged.

## Master §22 deliverable snapshot
1. Phase 03 COMM-V1-T04.
2. Repo main scaffold audited; no P01/P02 files changed.
3. Synthetic invoices, partial payments, refunds, daily report.
4. No DB/API changes.
5. Responsive form/UI; no stylus needed for billing.
6. Only synthetic/model permissions; real security and financial signoff NOT RUN.
7. Model test suite authored; execution evidence pending.
8. Full E2E integration/regression NOT RUN.
9. Tax/medical/privacy applicability review NOT RUN.
10. Browser prototype only, no supported customer deployment.
11. Draft source files and manual preview instructions.
12. No persistence, invoice compliance, live auth, UAT or recovery.
13. No backup/restore in scope.
14. Customer acceptance NOT RUN.
15. INTERNAL_FOUNDATION / NOT_READY.
16. READY FOR ISOLATED REVIEW; NOT READY FOR COMMERCIAL RELEASE.
