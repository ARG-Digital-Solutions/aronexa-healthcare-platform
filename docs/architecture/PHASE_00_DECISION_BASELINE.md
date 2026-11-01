# Phase 00 — architectural and security decision baseline
Status: RECOMMENDED FOR OWNER ACCEPTANCE; design decisions only, not tested implementations.
Authority: AroNexa Master Engineering Contract V3.0 (22 sections) and proposed Section 23 AI addendum. V3.0 supersedes earlier inconsistent product/colour/ordering guidance.

## Accepted-as-baseline recommendations pending owner's formal sign-off
1. One Spring Boot modular monolith, Java 21, Spring Security, Spring Modulith, Maven, PostgreSQL/Flyway; React/TypeScript/Vite. Avoid Kafka/Redis/Kubernetes and separate specialty products. Verify cycles/contracts in tests once code exists.
2. Shared patient, doctor, branch, encounter, order, consent, clinical record, invoice and audit; specialty extension entities only, preserving authorized longitudinal history.
3. Tenant ownership mandatory on operational tables; tenant-aware foreign keys and policy enforcement at API/service layer. Design for PostgreSQL RLS using non-owner non-BYPASSRLS application role, SET LOCAL for transaction-bound context and defensive fail-closed checks. Validate actual policy against connection pooling and bulk/reporting operations during Phase 01 before production; RLS alone never substitutes for object authorization.
4. RBAC plus explicit tenant/branch/doctor/patient/encounter object policies. Super Admin does not automatically see PHI; exceptional support requires distinct, audited, time-bound authorization subject to clinical/legal policy.
5. Module registry state and entitlements enforce on backend and in UI; deactivation preserves historical records. Offline and SaaS share core and adapt storage/licensing/communications; never imply synchronization before its design and tests.
6. Clinically authored orders are authoritative. Finalized prescriptions and discharge documents are immutable with version-linked amendments and clinician authorization; ink is separate from structured clinical data.
7. Option A + Option D: navy #102D54, medical blue #2563EB, teal #0D9488, background #F5F8FC, white #FFFFFF; accessible, low-click role dashboards.
8. AI is optional draft assistance with source provenance, human review and manual fallback; no autonomous diagnosis, prescribing or discharge authorization. External identifiable clinical data transmission requires prior contract, legal, security and privacy approval.
9. Phase order 00–14 as in PHASE_REGISTER; macOS and Android last. Per-phase owner authorization remains mandatory.

## Decisions intentionally deferred with release gates
- Formal fine-grained role/patient/branch matrix and emergency-break-glass workflow: Phase 01 design must precede identity/API coding.
- Exact RLS SQL policies, indexing and pooled-transaction tests: Phase 01 DB task prior to any multi-tenant customer data.
- Clinical data retention, patient rights, practitioner/ART/legal jurisdiction-specific obligations: qualified legal/clinical review before relevant live deployment.
- Offline encryption key custody, backup restore, licence continuity and support: Phase 07 approval.
- AI model/provider security contracts and acceptance corpus: before any identifiable PHI processing.
- Teleconsultation delivery phase: previously approved feature, absent from V3; retained in traceability, owner scope approval required.
- Definitive original phase-specific prompt series is not available for line-by-line verification. V3.0 is authoritative product scope; phase allocations remain proposed rather than falsely described as verbatim.

## Sign-off checklist
Owner: approve or request changes to baseline decisions. Security reviewer: accept tenant/object access threat model before Phase 01 related code. Clinical reviewer: approve clinical workflows before their feature releases. None of these sign-offs can be inferred from a merged PR.
