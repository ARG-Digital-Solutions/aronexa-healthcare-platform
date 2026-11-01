# Phase 00 architectural decision records — review draft
Status: PROPOSED. Authoritative source Master Engineering Contract V3.0; no application implementation.

## ADR-001: Unified modular monolith
Decision: Java 21 + supported Spring Boot/Spring Security + Spring Modulith, Maven, PostgreSQL/Flyway; React/TypeScript/Vite UI. One backend deployable, no independent specialty databases/services. Database-backed outbox + scheduler; no speculative Kafka/Redis/Kubernetes/workflow engine. Modules expose explicit APIs with no circular dependencies; enforce via Modulith/architecture tests. Rationale: maintenance by one developer, safety and modular growth. Review gate: dependencies diagram and isolation tests approved.

## ADR-002: Shared domain and extension model
Shared platform/tenant/branch/department/user/staff/doctor/patient/guardian/partner/consent/appointment/encounter/clinical history/orders/prescription/documents/invoice/payment/expenses/followup/notification/audit/config. Specialty entities reference shared patient and encounters; no duplicated masters or specialty attributes indiscriminately added to common tables. Contracts prevent cross-domain table/repository access. Retain authorized longitudinal history during facility-type expansion.

## ADR-003: Tenant, branch, object authorization
Use tenant_id on tenant-scoped entities, compound constraints for ownership, application RBAC plus resource/branch/doctor policies. Consider PostgreSQL RLS defense in depth: non-owner app role, no BYPASSRLS, transaction-scoped tenant context under pooled connections and explicit fail-closed tests. Privileged migration role separate. Super Admin cannot automatically read clinical records; break-glass process requires separate design/review.

## ADR-004: Configuration and module lifecycle
Registry: module ID/version, implemented/released, dependencies, entitlement/licence, tenant activation, typed config and audit. Backend denies disabled modules, UI hides them; deactivation retains historical clinical/financial data. State transition validation and least-privilege admin authorization required.

## ADR-005: Clinical record integrity
Structured medication orders authoritative, clinician final approval, immutable final prescriptions and append-only amendments, audited attribution and export. Digital ink is a separate versioned layer with mouse/touch/stylus fallback and printed view; handwritten signature picture does not imply legally valid digital signature. Medication autocomplete never issues treatment autonomously.

## ADR-006: Deployment model
Common domain code, adapters for ONLINE_SAAS / OFFLINE_STANDALONE / DEDICATED_INSTALLATION; independent acceptance suites. SaaS plans and data export separate from patient billing. Windows local runtime/backup/printing without mandatory activation; signed licences use public-key verification and preserve patient record availability after expiry. Do not assume synchronization. Native macOS then Android only after common/specialty phases.

## Unresolved architectural decisions
Precise PostgreSQL RLS design, permission matrix, clinical record retention, backup tenant granularity, signed licence continuity UX, teleconsultation implementation phase, detailed regulatory applicability, deployment topology and clinical data contracts require subsequent approved design tasks and stakeholder review.
