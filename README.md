# AroNexa Healthcare Platform

AroNexa is an evolving, configurable clinic, hospital and specialty healthcare platform.

## Current engineering status (November 2026)
- Phase 00 architecture and Master Engineering Contract V3.0 approved.
- Phase 01 P01-T01 through P01-T05-D source and security foundation merged on main: Java 21, Spring Boot, Spring Modulith, React/TypeScript/Vite, PostgreSQL/Flyway V1–V6, authentication, tenant-aware organization RLS and scoped organization access.
- **No clinical workflows, patient/doctor models, interactive login UI, receptionist dashboard, production release or customer deployment are available.**
- **Not ready for real patient information**. Authentication-role SQL isolation, clinical object authorization and operational security controls are incomplete.

## Review and local runtime
- [Phase 01 Security Closure and Local Runtime Acceptance](docs/phases/P01_SECURITY_CLOSURE_ACCEPTANCE.md) — the authoritative current local acceptance matrix and Windows runbook.
- [P01-T05-D build log](docs/phases/P01_T05D_BUILD_LOG.md) — historical CI evidence for 53 Java tests and V6.
- GitHub Actions: PR Quality Gates (application unit/integration tests), and Dev Runtime Acceptance (fresh isolated PostgreSQL + actual dev profile).

## Governance
Do not copy CareNexus source. All new implementation tasks require explicit owner authorization. No merge, deployment, destructive migration, customer data or advancement to another phase without approval. Run development services only against isolated synthetic/non-customer data.
