# AroNexa permanent Codex rules
Scope: repository-wide. Read this file before every task.
1. Only work on the user-approved task. Do not move phases, merge, deploy, or change production settings without approval.
2. Never copy or depend on CareNexus source. Inspect existing files before editing; never overwrite without express approval.
3. Prefer modular monolith (Java 21, Spring Boot, Spring Modulith, PostgreSQL/Flyway; React/TypeScript). Enforce acyclic module dependencies and published interfaces.
4. Enforce tenant, branch, role and resource authorization on server and in persistence. Fail closed; use tenant-scoped constraints and test PostgreSQL RLS with non-privileged application roles.
5. Preserve clinical integrity: patient identifiers, finalized immutable prescriptions, audited amendments, clinician verification and versioned documents.
6. Do not expose real patient information in fixtures, logs, screenshots or prompts; handle consent and disclosures appropriately.
7. Review migration safety, roll-forward/recovery and backup/restore before schema changes. No destructive migration without explicit approval.
8. Require focused unit, PostgreSQL integration, tenancy-negative, architecture, security and regression tests appropriate to the task.
9. Document actual commands, outcomes, failures, changed files and open risks in a build log; never claim tests passed if not executed.
10. Small PRs: one bounded task with requirement IDs, acceptance criteria, review notes, dependency changes, and evidence. No silent broad refactoring.
11. Phase 00–14 mapping is provisional until checked against the original consolidated roadmap. Do not infer missing requirements.
12. Every customer-facing release requires applicable clinical review, data protection/security review, rollback/restore rehearsal, and explicit owner approval.

## Contract V3.0 mandatory additions
13. Master Engineering Contract V3.0 is authoritative for product scope; earlier phase assignments remain provisional where not explicitly specified. Never claim an unexecuted test passed.
14. Use a database-backed outbox/scheduler; do not add Kafka, Redis, Kubernetes, microservices, workflow engines, or separate databases without documented justification.
15. Central registry only activates implemented, released, entitled and clinically authorized modules; disabled modules must deny API access and preserve historical records.
16. Maintain Option A + Option D design, tokens navy #102D54, blue #2563EB, teal #0D9488, background #F5F8FC, white #FFFFFF; consistent role-specific, accessible workflows.
17. Phase 02 includes reusable clinical digital ink: mouse/touch/stylus, undo/redo, eraser, diagrams, drafts, zoom, device-dependent pressure/palm rejection, printable PDF, versioning and authorization. Structured medication is authoritative; signature images are not assumed to be legal digital signatures.
18. Separate ONLINE_SAAS, OFFLINE_STANDALONE and DEDICATED_INSTALLATION adapters from common business logic. Offline clinical continuity and signed offline licensing are mandatory; never assume synchronization exists.
19. Distinguish IUI versus IVF/ICSI flows; require specialist review, consent, embryology traceability, chain of custody and ART regulatory review.
20. Every feature phase must report a release classification INTERNAL_FOUNDATION, CUSTOMER_PILOT, PRODUCTION_RELEASE or NOT_READY, and all 16 V3 Section 22 report elements. A phase is never production-ready on compilation alone.
21. macOS and Android native packaging come last, after the shared platform and specialties; Android cannot reuse the desktop Spring Boot/PostgreSQL installation model unchanged.
