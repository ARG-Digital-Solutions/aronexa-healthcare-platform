# AroNexa requirements traceability — P00-T02 reconciled draft

Status: REVIEW REQUIRED. Cross-checked against the supplied "AroNexa Healthcare Platform — Master Engineering Contract — Final" (16 sections) and approved requirements in prior conversation; not checked line-by-line against the separate original Phase 00–14 prompts, which are not yet available in full. Phase allocation below is proposed, not asserted original. No features implemented or tests run.

Columns: Requirement ID | Scope and objective | Owning module | Planned phase | Acceptance/evidence required.
| ID | Requirement | Module | Phase | Acceptance/evidence |
|---|---|---|---|---|
| CORE-01 | One configurable platform for single/multi-doctor clinics, hospitals, fertility centres, multi-branch groups; preserve history when expanding | platform | 00 design; 01+ implement | Facility-preset growth scenario, single patient/doctor identity |
| CORE-02 | Central module registry (identifier/version/dependencies/platform availability/tenant entitlements/activation/config scopes/history), disable without deleting data | platform/config | 00 design; 01 foundation; 06 subscriptions | Dependency-validation, entitlement-denial, history-preservation tests |
| ARCH-01 | Java 21, supported Spring Boot, Spring Security, React/TS/Vite, Maven, PostgreSQL/Flyway, one backend/frontend/database; no unjustified microservices/Kafka/K8s/Redis | architecture | 00/01 | Architecture decision and build verification |
| ARCH-02 | Spring Modulith boundaries, owned data, no cycles, published interfaces, future adapters | architecture | 00/01+ | Architecture tests and dependency map |
| SEC-01 | Tenant, branch, role, doctor and object authorization; super-admin has no default clinical-record privilege | identity/security | 00 design; 01+ enforce | Negative tenant/role/doctor tests using real PostgreSQL |
| SEC-02 | Secure onboarding, password hash/recovery, first-login change, revocable/expiring sessions, rate limiting, audit | identity/security | 01 | Authentication lifecycle and abuse tests |
| SEC-03 | Tenant-aware constraints, least-privileged DB role, transaction-scoped RLS context, isolated migrations and audit | data/security | 00 design; 01+ | Cross-tenant reads/writes blocked incl. pooled connections |
| UX-01 | Navy/medical-blue + teal design; role landing, simple responsive keyboard-friendly workflow, reusable components | design system | 00 design; 01+ | Accessibility and role-workflow review |
| PAT-01 | Self- and reception-assisted registration, walk-ins, duplicate matching, no mandatory ABHA/Aadhaar, patient history and consent | patient | 01 | Duplicate and permissions tests |
| PAT-02 | Patient access to authorized documents | patient portal/documents | 01 foundation; 04 delivery | Ownership and authorization negative tests |
| APT-01 | Requests/booking, doctor schedules, branch queue/check-in, concurrency-safe slots, idempotent operations | appointment | 01 | Concurrent booking/queue/idempotency tests |
| CLN-01 | Consultation: complaint, history/allergies, vitals, exams, diagnoses, investigations, clinical notes, follow-up and prior history | clinical | 02 | Clinician workflow and longitudinal history tests |
| RX-01 | Smart Rx catalogue/generic-brand search, dose/route/strength/frequency/timing/duration, favourites, recent, quick templates, previous Rx reuse | prescription | 02 | Validation and clinician acceptance |
| RX-02 | Authorized Rx preview, doctor qualification/signature, immutable finalized Rx, amendments and clinical audit | prescription | 02 | Finalization immutability, amendments, authorized PDF tests |
| RX-03 | Suggestions never auto-prescribe; safety warning scope transparent, doctor final authorization | prescription | 02 | Non-auto-finalization and adverse-path tests |
| FORM-01 | Stylus annotations on suitable OPD/IPD forms with attribution/versioning; structured medication remains mandatory | forms | 02/09 | Annotation persistence, export and access tests |
| FIN-01 | Fees, invoices, partial payments, refunds, receipts, vendor expenses, safe adjustments, decimal money | billing/expense | 03 | Ledger/history, reconciliation and adjustment tests |
| RPT-01 | Daily/monthly/yearly expense and revenue, outstanding, doctor/branch/department/OPD/follow-up reporting; specialty reports as features arrive | reporting | 03; 09–12 expand | Reconciled totals and role-scoped reports |
| FUP-01 | Today/upcoming/overdue/completed/missed queues, callback tasks, patient engagement | followup | 03 | Status lifecycle, duplicate/reminder tests |
| MSG-01 | Official WhatsApp Business/SMS/email/manual WhatsApp/call-task adapters, consent/opt-out/templates, verified recipients | communications | 04 | Mock/provider contract tests; no unverified live sending |
| MSG-02 | Transactional outbox, retry, dedupe, signed webhook verification, delivery states | communications | 04 | Retry/replay/failure/receipt tests |
| DOC-01 | Secure prescription/report notices and access; no unrestricted public clinical URLs; paperless flows | documents | 04 | Authorization, expiry, audit and revoke tests |
| TEL-01 | Teleconsultation waiting/join/expiry, tenant/patient/clinician authorization, provider abstraction | telehealth | 04 requirement/design; implementation phase to confirm | Expiry, privilege and session-leak negative tests |
| QA-01 | Pilot review, clinical UAT, regression, security, backup/restore and release gate | QA/release | 05 and every customer release | Signed acceptance matrix and actual commands/outcomes |
| SAAS-01 | Tenant onboarding, subscriptions, entitlements, module activation and billing lifecycle | subscriptions | 06 | Tenant isolation, entitlement and state transitions |
| OFF-01 | Windows offline, first-run setup, annual licence, encrypted backup/restore, single-click installer | desktop | 07 | Offline install, licensing, backup restore, no Docker for clinic user |
| DEP-01 | Dedicated isolated customer installation and environment separation | deployment | 08 | Isolated staging/production, recovery and installation tests |
| IPD-01 | Admissions, bed/ward, care chart, clinical orders, transfers, discharge and billing | inpatient | 09 | Admission/discharge, patient lineage, ward permission tests |
| PHA-01 | Dispensing and medicine inventory, prescription linkage, stock traceability | pharmacy | 10 | Stock movement and authorized dispensing tests |
| LAB-01 | Tests/orders/specimens/results and role-controlled release | diagnostics | 10 | Orders-to-results traceability tests |
| FER-01 | IUI/IVF cycles, protocols, medication/clinical orders, lab workflows and chain of custody | fertility | 11 | Clinician-led workflow acceptance and chain-of-custody tests |
| REG-01 | Versioned Indian compliance register: DPDP and rules, practitioner/EHR, ABDM if adopted, ART where applicable, Maharashtra facility rules, incident reporting | compliance | 00; every release | Reviewed applicability/effective dates and legal/clinical sign-off |
| INT-01 | Provider-neutral integrations, versioned APIs and authorized analytics | integrations | 12 | Contract/security/reconciliation tests |
| MAC-01 | macOS application sharing core semantics | macOS | 13 | Installation, security and desktop compatibility |
| AND-01 | Android application sharing core semantics | Android | 14 | Mobile authorization, session and user-journey tests |

## Cross-phase invariants
- Shared patient, doctor, staff, facility, encounter and longitudinal clinical history; no duplicated masters per specialty.
- No CareNexus code reuse; no destructive schema or migration without approval; no implementation without approved task.
- Audit sensitive access/changes; protect real patient information; encryption, restore and incident handling appropriate to deployment.
- Preserve historical prescriptions, invoices, transaction adjustments and clinical data.
- Every phase: plan -> task approval -> focused implementation -> actual build/unit/integration/security/regression evidence -> PR review -> explicit approval; no auto-merge/deploy/advance.
- Clinical release must not ship with high-impact patient-safety, authorization, tenancy or reconciliation failures.
- Test states only PASS / FAIL / BLOCKED / NOT RUN, with reproducible evidence.

## Open reconciliation points
1. Obtain original complete Phase 00–14 prompt series to verify exact sequencing, module assignments and scope; this document is a corrected coverage baseline, not a verbatim reconstruction.
2. Confirm teleconsultation delivery phase (Phase 04 is a provisional mapping; provider selection and clinical expectations unresolved).
3. Confirm patient portal MVP depth and offline synchronization semantics before coding.
4. Review current regulatory obligations and medical/ART applicability with qualified clinical/legal reviewers; do not claim compliance by design alone.

## Master Engineering Contract V3.0 — coverage additions (supersedes earlier assumptions)
V3.0 is the authoritative product specification (22 sections). Mappings below add explicit coverage; pre-existing requirement IDs and planned phases remain subject to original phase-prompt verification. No build/test evidence exists yet.

| ID | V3 section | Additional requirement | Proposed phase | Evidence/acceptance |
|---|---|---|---|---|
| CORE-03 | 2,4 | Day-care facilities; guardian/partner relationships; specialty extension entities and unified longitudinal records | 00 design/01,11 | Facility expansion and identity/consent tests |
| MOD-01 | 5 | Registry implementation/release/licence/clinical approvals; deny disabled-module APIs, preserve records | 00 design/01,06 | Gate and historical data tests |
| SEC-04 | 6,7 | Admin support exceptional audited clinical access; file validation, TLS, incident response | 00 design/01+ | Privilege escalation, access log, file security tests |
| UX-02 | 8 | Approved Option A/D tokens #102D54, #2563EB, #0D9488, #F5F8FC, #FFFFFF; responsive role dashboards | 00/01+ | Design token and accessibility review |
| FORM-02 | 9 | Digital ink: mouse/finger/stylus, undo/redo, eraser, zoom, draft, diagram overlays, optional pressure/palm rejection, PDF, ink/structured separation | 02 and reusable in 09,11 | Save/reload, export, finalize, amendment, device fallback tests |
| RX-04 | 10 | Quantity, units, manual medicine entry, hospital templates, supported allergy/duplicate alerts | 02 | Clinical approval and invalid-input tests |
| APT-02 | 11 | Cancel/reschedule, patient preferences and tokens | 01 | Slot and race-condition acceptance |
| FIN-02 | 12 | Separate SaaS subscription invoices from patient billing | 03/06 | Reconciliation and data ownership tests |
| MSG-03 | 13 | Offline graceful degradation; audited delivery failures and explicit data disclosure restrictions | 04/07 | Provider outage, delayed retry, consent tests |
| IPD-02 | 14 | Nursing observations, medication administration, procedures | 09 | Dose/event audit and nurse authorization tests |
| PHA-02 | 14 | Batch, expiry, vendor, purchasing, returns and stock reconciliation | 10 | Expired-stock and stock-ledger tests |
| LAB-02 | 14 | Sample validation and result authorization | 10 | Chain and release tests |
| FER-02 | 15 | Distinct IUI and IVF/ICSI, fertility partner consent, stimulation, oocyte/semen, embryo/cryopreservation, ART records | 11 | Specialist-reviewed clinical chain-of-custody scenarios |
| REG-02 | 16 | Pharmacy/lab/facility laws, financial and incident obligations and jurisdiction-specific check | 00, every release | Versioned legal/clinical applicability signoff |
| SAAS-02 | 17.2 | Trials, monthly/annual plan transitions, payment integration, grace, data export/clinical continuity | 06 | Subscription lifecycle and isolation tests |
| OFF-02 | 17.3-17.4 | Local standalone operations without internet; printing; secure installer/updates; asymmetric signed offline licence with limits and continuity after expiry | 07 | Tamper, renewal, offline install, expired-license continuity, restore tests |
| DEP-02 | 17.5-17.6 | Customer-isolated private cloud/on-prem and independent deployment-mode verification; no assumed sync | 08 | Tenant/dedicated install smoke and restore tests |
| REL-01 | 18,20,22 | Customer-usable increments, release classifications, all relevant tests, 16-field report and explicit approval | all | Actual evidence, customer UAT, signed gate |
| MAC-02 | 21 | Supported hardware, signing/notarization, installer, backups and licences | 13 | Supported macOS distribution checks |
| AND-02 | 21 | Tablet-first app, secure device login and stylus; no desktop backend/database reuse; sync only if separately approved | 14 | Mobile security/compatibility and release signing tests |

## V3 coverage control
Sections 1-7: scope, architecture, shared domains, registry, roles, security. 8-11: design, ink, OPD/Rx, patient/appointments. 12-16: finance, communications, IPD/pharmacy/lab, fertility, regulation. 17-22: SaaS/offline/dedicated, deliverable phases, change control, quality gates, cross-platform and phase report. All sections now have explicit coverage through the original matrix and V3 additions; NOT implementation evidence and NOT a line-by-line comparison with unavailable original separate phase prompts. Teleconsultation was an earlier approved requirement absent from V3 text; retain TEL-01 as pending owner scope decision rather than silently remove.

## P00-T10 AI requirements (proposed supplement to V3.0)
AI-CORE-01 Phase 00 design + first AI feature implementation: shared authorized, auditable optional provider-neutral AI drafting module. AI-OPD-01 and optional AI-RX-01 Phase 02; AI-MSG-01 Phase 04; flagship AI-IPD-01/02 Phase 09; AI-FER-01 Phase 11; AI-OPS-01 Phase 12; AI-CLIENT-01 Phase 13–14. Source references, clinician signoff, PHI controls and manual fallback required. Full acceptance matrix: docs/requirements/AI_ASSISTANCE_REQUIREMENTS.md. This does not constitute automatic approval of new capabilities.

## P01-T06 scoped implementation/evidence addendum — 2026-11-04

The earlier "no implementation/test evidence" text describes the historical planning baseline. This addendum records only P01-T06 evidence reviewed at main adc8e98096ff85e7866b66a4a58838d4b4bfc09c; all original requirement wording and provisional phases remain unchanged. No whole-product requirement is marked complete.

| Existing ID | Current implementation scope | Evidence reviewed | Missing work / acceptance limits |
|---|---|---|---|
| CORE-02 | PARTIAL: minimal registry, entitlement/activation flags and audit | V7; ModuleAccessService; disposable checks; Windows Maven/build output | Version/dependency/platform/configuration metadata; provisioning UX; full lifecycle |
| MOD-01 | Foundation implemented; technical checks PASS within supplied scope | Default deny; clinical gate test code; RLS/grants; synthetic deactivation/history; regression output | Actual future APIs must invoke gate plus role/object authorization; no clinical records/APIs exist |
| SEC-03 | PARTIAL: module-state RLS and restricted role grants | Two-tenant disposable checks; runtime TCP login; cross-role denial | Broader auth-role exposure/privilege review remains open; no blanket security completion |
| ARCH-01 | PARTIAL: tested Java 21/PostgreSQL/Flyway/React TS Vite foundation | Windows Preflight and Tests PASS | Supported-version/dependency review and wider architecture scope remain separate |
| UX-01 / UX-02 | PARTIAL: desktop navy/teal scaffold | Owner screenshot, HTTP 200, visual review | Role landing pages, workflows, tablet/accessibility acceptance; stale Task 01 badge |
| QA-01 / REL-01 | PARTIAL: internal foundation technical acceptance | Backup/restore rehearsal; V7 validation; runtime and regression results | Raw report archival, customer UAT, deployment-specific gates and owner closure sign-off |
| SAAS-01 | PARTIAL: reusable entitlement foundation only | Registry flags and denial checks | Tenant onboarding, subscriptions, payments and commercial lifecycle NOT IMPLEMENTED by P01-T06 |

Source: [P01-T06 closure review](../phases/P01_T06_CLOSURE_REVIEW_2026-11-04.md), [evidence archive](../phases/evidence/P01_T06_20261104/EVIDENCE.md). Exact Windows test totals/skips not supplied. Preservation evidence is table-count equality, not row-by-row or clinical-record integrity. Disposable V6 restoration passed; operational post-V7 recovery cutover NOT RUN. No new requirement IDs or phase allocations inferred. Owner closure approval remains pending.
