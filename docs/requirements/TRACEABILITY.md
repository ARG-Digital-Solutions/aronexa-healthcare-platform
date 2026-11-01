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
