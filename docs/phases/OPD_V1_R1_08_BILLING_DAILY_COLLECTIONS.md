# R1-08 — Basic OPD Billing and Branch Daily Collections

**Status:** Source committed; exact-SHA R1-08 verification NOT YET RUN.
**Classification:** SYNTHETIC TEST-ONLY; NO real invoices, receipts or money movement.
**Release disposition:** NO-GO for live patients, actual accounting or production.

## Dependency and scope
- Stacked on Development R1-07 PR #114 accepted local test-only exact SHA
  acc31d122dd3414a90cadee8357298936923c8a5.
- R1-07 owner evidence: full 272 Maven tests, 0 failures/errors/skips,
  real authenticated HTTP + disposable PostgreSQL, all frontend+doctor
  preview builds PASS and QA Central #35 Chromium 7/7 PASS.
- Reuses existing COMM-V1-T04 synthetic billing model's financial rules:
  **integer paise**, bounded values, immutable financial events,
  partial payment, and date-scoped report metrics.
- Connected incremental scope is deliberately smaller than the older
  standalone finance prototype: server-priced, one-service consultation bill
  from a checked-in appointment **with a saved consultation**, a fictional
  cash payment ledger and a branch/day collection report.
- No refunds, invoice adjustments, actual payment gateway, UPI/card
  verification, GST invoice, receipt PDF, signature or billing SaaS product.

## Backend proof — disposable Testcontainers PostgreSQL only
- New backend/src/test/resources/db/test/appointment-candidate-r108.sql
  is installed only in the existing automatically destroyed PostgreSQL 17
  fixture, after the R1-04...R1-07 SQL proof scripts. No production Flyway
  V1–V9 change, no customer DB modification and no runtime SQL role grants.
- Fee schedule: exact tenant/branch/service-code rows seeded for fictional
  clinic staff. Consultation fee = 50000 paise (₹500.00) in the synthetic
  test setup, configured on server, not accepted from browser.
- Immutable opd_invoice: server-assigned UUID and numeric bill reference,
  exact appointment/tenant/branch/patient/doctor composite FK, positive
  integer-paise fee snapshot, unique appointment and request idempotency.
  A replay of the same request returns original invoice; different request
  for already billed appointment returns 409.
- Immutable opd_cash_event: server-issued record reference, exact
  invoice/tenant/branch FK, unique payment-request ID, timestamp, actor,
  amount in positive integer paise, no alterations or deletes.
  Payment is *manually recorded fictional cash*, not proof that money
  actually moved or was reconciled.
- Posting a cash event serializes on the invoice row lock, compares
  against current outstanding balance, rejects overcollection, and prevents
  concurrent double collection. Same request/amount/invoice is idempotent;
  replay with changed amount is a conflict.
- Read audits store scope/actor/date only and exclude any demographic
  details, clinical notes, medicine orders and payment credentials.
- All GET/POST HTTP handlers exist ONLY in the nested Java test candidate,
  behind the test & appointment-candidate & !dev & !prod profile.
- Billing Staff role is provisioned ONLY inside the disposable fixture
  with permissions OPD_BILL_READ, OPD_BILL_CREATE, OPD_PAYMENT_POST and
  OPD_DAILY_REPORT_READ; Reception/Doctor cannot borrow these permissions.
- Every action rechecks current DB-backed bearer session, role, branch,
  membership, OPD entitlement/module and visit ownership. Bill create
  additionally requires CHECKED_IN and a saved consultation. No Rx draft
  is required to bill a consultation.

### Test candidate operations
- GET /api/v1/branches/{branchId}/appointments/{appointmentId}/billing-context
  returns the exact authorized patient, doctor, fee and appointment scope
  without demographic details.
- POST /api/v1/branches/{branchId}/appointments/{appointmentId}/bill
  accepts only requestId and serviceCode=OPD-CONSULTATION; server derives
  patient, doctor, amount, bill number and creation time. No price override.
- GET /api/v1/branches/{branchId}/appointments/{appointmentId}/bill
  reads UNPAID/PARTIAL/PAID balance with no-store.
- POST /api/v1/branches/{branchId}/appointments/{appointmentId}/bill/cash
  accepts invoiceId, unique requestId, amountPaise, checks exact
  invoice/patient/branch and outstanding amount under lock.
- GET /api/v1/branches/{branchId}/billing/daily?date=YYYY-MM-DD
  produces branch-scoped server-calculated invoiceCount, billedPaise,
  collectionsPaise and outstandingPaise; timestamp days use the clinic's
  Asia/Kolkata zone. Invoice count/amount = invoice creation day;
  collection amount = cash posting day; outstanding = *current unpaid
  amount on bills created that day*, not a historical end-of-day snapshot.
  Test endpoint allows only dates from 90 days ago through tomorrow.
- All responses have no-store cache policy. Denials do not reveal notes,
  patient names or medicine details.

## Isolated Billing Staff preview
- frontend/connected-billing-preview.html and
  frontend/src/connected-billing/ConnectedBillingPreview.tsx.
- Local-only Vite port 5178 via npm run dev:connected:billing:test.
- Separate Billing Staff sign-in, exact branch and appointment input,
  independent server-authorized billing context lookup, read/create bill,
  partial fictional cash, current outstanding balance and daily report.
- No browser storage/cookies, demo patient ID fallback, client-set fee,
  unverified payments, or live clinic data; signout clears memory.
- frontend/src/billingCandidateClient.ts validates exact patient/tenant/
  branch/doctor/appointment, server fee, invoice ID/number, monetary sums,
  statuses, report scope/date and no-store. Cash values parse decimal INR
  once into integer paise. On failure, active balance is discarded and
  users must reopen the server ledger before attempting another action.
- Clearly marked FICTIONAL/INVALID financial workspace. No ability to
  issue statutory invoices or payment receipts.

## Acceptance plan — NOT YET RUN on R1-08 SHA
1. Five new real authenticated HTTP + disposable PG17 JUnit scenarios
   appended to AppointmentCandidateHttpAcceptanceTests:
   server fee/immutable/idempotent bill creation;
   partial/full/idempotent cash plus Kolkata daily report;
   booked/unconsulted/cross-tenant/wrong-branch/unsupported-role denial;
   permission/module/session revocation;
   two simultaneous cash posts that would exceed the balance.
2. Ten Node client tests for independent patient context, server fee,
   INR integer conversion, payment conflicts, forged receipt/report scope,
   signed-in Billing role, revoked sessions, clear/dispose.
3. Existing R1-04→R1-07 full frontend/backend registration, appointments,
   consultation and prescription regression remains mandatory.
4. Separate R1-08 Billing preview TypeScript + Vite build.
5. QA Central R1-08 independent opt-in Chromium 10-case suite runs with
   mocked HTTP. It verifies UX only, NOT real PostgreSQL persistence.

Run from an isolated exact-SHA Windows worktree with Docker running
and **no Vite process from that same worktree** (native esbuild/Rollup
files remain locked if the preview is running during npm ci):

    powershell.exe -NoProfile -File .\scripts\Test-Opd-R1-08-Billing-Increment.ps1 -ExpectedCommit EXACT_R108_SHA

Separate browser QA, after application acceptance:
- From R1-08 DEVELOPMENT frontend worktree that is not being npm-ci'd:

    npm.cmd run dev:connected:billing:test

- From QA Central R1-08 isolated worktree:

    npm.cmd ci
    $env:ARONEXA_BILLING_BASE_URL="http://127.0.0.1:5178/connected-billing-preview.html"
    npx.cmd playwright test -c playwright.r1-08.config.mjs --project chromium-desktop --reporter=list

Capture exact SHA, logs and counts before marking acceptance PASS.

## Commercial release blockers
- Test adapter uses a privileged migrator connection ONLY in automatically
  destroyed PG17. Real restricted SQL writer, RLS, staff/branch/session
  security, approved registration/appointment/encounter/billing migrations
  and independent security review are not yet delivered.
- Correct sequential invoice numbering for appropriate legal entity/
  financial year, applicable Indian tax rules, settlement reconciliation,
  payment methods, refund/adjustment controls, receipt issuance, time-zone
  edge cases, financial data retention, access review and independent
  accounting acceptance are **outside this synthetic increment**.
- Offline Windows encrypted backup/verified restore, installer, licensing,
  production monitoring, accessibility and first real pilot are separately
  required; do not treat browser QA as a commercial release approval.
- Keep Development and QA pull requests DRAFT / UNMERGED. No production
  grant, activation, merge, deployment, actual payment or live patient use.
