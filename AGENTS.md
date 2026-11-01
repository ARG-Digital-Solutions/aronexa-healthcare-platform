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
