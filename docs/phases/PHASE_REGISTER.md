# AroNexa Phase 00–14 register — reconciled coverage draft

Status: REVIEW REQUIRED. Phase allocations remain provisional until the complete original consolidated Phase 00–14 prompts are compared line-by-line. Master Engineering Contract — Final is the currently checked functional baseline.

State machine per phase: PROPOSED -> APPROVED -> IN_PROGRESS -> VERIFIED -> OWNER_ACCEPTED. Do not auto-advance, merge or deploy. No application code is authorized by this planning document.

| Phase | Scope and added acceptance gates | Dependencies | Complexity |
|---|---|---|---|
| 00 | Repository inventory; architecture ADRs; Spring Modulith and module registry; tenant/RLS/RBAC threat model; design system; clinical safety and regulatory register; AGENTS.md; final traceability and task approvals | none | M |
| 01 | Tenant/facility/branch/roles; patient self/reception registration, duplicate detection; first-login and session controls; appointments, check-in, concurrent booking, minimal patient self-service | 00 accepted | L |
| 02 | OPD encounters, longitudinal history, allergies/vitals, Smart Rx, templates/autocomplete, clinician-controlled finalization, immutable Rx/amendments, stylus annotation on applicable forms | 01 | L |
| 03 | Fee catalogue, invoices, partial payments/refunds, expense reports, finance reconciliation, follow-up status and callback queues | 01–02 | L |
| 04 | Authorized paperless documents, consent-based WhatsApp/SMS/email/manual handoff, outbox/retry/dedupe/webhooks; teleconsultation design/phase assignment to confirm | 01–03 | L |
| 05 | First customer clinic pilot, E2E Playwright flows, tenant/security tests, clinical UAT, backups/restore, readiness report | 01–04 | L |
| 06 | SaaS tenant provisioning, subscriptions, module entitlements/registry lifecycle, billing and limits | core registry + 05 | L |
| 07 | Windows offline, first-run wizard, annual licence, backup/restore and user-friendly installer | 05 + offline risk review | L |
| 08 | Dedicated hospital installation, isolation, secrets, migration and recovery procedures | 05 | M |
| 09 | IPD admission, wards, care, clinician forms/stylus, discharge, finances | 02–05 | L |
| 10 | Pharmacy/inventory and lab order/specimen/result flows, common data, reporting | 02; 09 where relevant | L |
| 11 | IUI/IVF clinical cycles, lab-linked traceability, chain of custody, regulatory and clinical review | 02,10 | L |
| 12 | Provider connectors, interoperability and secure analytical reporting, incl specialty reporting | relevant modules | L |
| 13 | macOS client sharing authorization and clinical semantics | 05; desktop design | L |
| 14 | Android client with secure sessions and approved role workflows | 05; API readiness | L |

## Dependency gates
- Module registry and entitlement enforcement design in 00; implement foundational checks in 01 before SaaS portal in 06.
- OPD Rx and shared medicine catalogue contracts before dispensing; shared encounter/order foundations before IPD, lab and fertility.
- No tenant context leakage through pooled connections; super admin has no default patient-record access.
- No external messaging or teleconsultation goes live without reviewed consent, privacy, access, expiry and provider test evidence.
- Offline and SaaS editions share domain rules but require explicitly reviewed data/storage/synchronization models.
- Specialty features extend shared patient/doctor/encounter models; no duplicated masters.

## Plus-sized Codex task pattern
Task card: ID; phase; requirement IDs; precise in-scope files; excluded files; dependencies; expected outputs; complexity S/M/L; executable acceptance tests; negative cases; rollback; evidence log; explicit owner approval. One bounded task per Codex run.

### Phase 00 remaining
- P00-T02: Correct traceability and phase register; validate against master engineering contract (completed draft, awaiting exact phase-series verification).
- P00-T03/T04: Architecture ADR and full threat-model review; RLS negative test design.
- P00-T05: Reception and OPD Rx wireframe and accessible design acceptance.
- P00-T06: AGENTS.md final review and signoff.
- P00-T07: Detailed execution-ready Phase 01 task cards and later phase gates.
- P00-T08: Documentation verification, review report, owner acceptance.

## Universal phase acceptance
No PASS without actual command/evidence; tests may be NOT RUN when code does not exist. Each customer-facing release demands clinical authorization where applicable, real PostgreSQL cross-tenant checks, financial consistency, protected documents, regression, security review, restore rehearsal, known-issues review and explicit release approval.

## Version 3.0 authoritative scope clarifications
Preserve 00–14 numbering. This register remains planning-only. Web/SaaS first; then Windows offline and dedicated; then IPD/pharmacy/lab/fertility/integrations; macOS and Android LAST. No premature platform packaging.

| Phase | Additional V3 acceptance scope |
|---|---|
| 00 | Authoritative V3 matrix, ADRs, versioned regulatory register, threat model, approved Option A/D tokens, registry state machine and common/extension entity map |
| 01 | Day-care/facility presets, role dashboards, secure admin access, patient/guardian identity, cancellation/rescheduling, module-gated APIs |
| 02 | Full reusable ink engine and tests, supported input fallbacks, clinically safe structured Rx and clinician-authorized amendments |
| 03 | Separate patient billing from SaaS plans, reconciliation, refund and follow-up reporting |
| 04 | Outbox/scheduler, consent, verified recipients, no public PHI links and offline fail-safe delivery model; teleconsultation remains provisional |
| 05 | First customer pilot with actual Playwright, Testcontainers, clinical UAT, recovery and security evidence |
| 06 | Subscription trials/plans/renewal/change/cancellation/grace/export; module entitlement enforcement |
| 07 | Windows standalone installer, local clinical continuity, offline print, secure backup, asymmetric signed licence and safe post-expiry record access |
| 08 | Dedicated cloud/on-prem installation with isolated secrets/storage/DB; independently tested deployment |
| 09 | Nursing, observation, medication administration, procedure and auditable inpatient forms |
| 10 | Pharmacy batch/expiry/purchase/return, lab samples/result authorization and financial integration |
| 11 | Distinct IUI/IVF/ICSI paths, consent, stimulation, gamete/embryology, cryostorage and chain-of-custody tests |
| 12 | Approved connectors and scoped analytics |
| 13 | macOS runtime, signing/notarization where required, hardware and lifecycle tests |
| 14 | Android tablet workflows, mobile security, stylus, device compatibility; do not embed desktop PostgreSQL/Spring unchanged |

All feature phases must be a coherent customer-usable increment only when release gate evidence is satisfied. V3 release classifications: INTERNAL_FOUNDATION, CUSTOMER_PILOT, PRODUCTION_RELEASE, NOT_READY. Every phase must produce the 16-item report in RELEASE_GATE.md. Separate original phase prompts remain outstanding for exact task-by-task comparison.
