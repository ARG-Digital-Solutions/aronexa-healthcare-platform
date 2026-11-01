# Phase 01 candidate Codex task cards — not approved to execute
All tasks are independently scoped and require explicit approval. Include changed-file impact review, migration risk, tests and PR evidence per AGENTS.md. Final tasks may be revised when phase 00 acceptance is signed.

| Task | Small deliverable | Prerequisite | Required evidence | Complexity |
|---|---|---|---|---|
| P01-T01 | Empty Java21/Spring/Modulith + React TS Vite project scaffold and CI | 00 accepted | Backend/frontend builds, lint, secrets baseline | M |
| P01-T02 | PostgreSQL/Flyway local infrastructure, isolated environments and test containers | T01 | Clean and repeatable migration on isolated DB | M |
| P01-T03 | Tenant, facility/branch, department shared entities and constraints | T02 | Referential isolation and migration tests | M |
| P01-T04 | Authentication lifecycle, user/role/permission model | T03 | Login/revocation/rate-limit negative tests | M |
| P01-T05 | Tenant/branch/object enforcement, RLS and pooled-connection tests | T04 | Cross-tenant/cross-branch/doctor denial | L; split when scoped |
| P01-T06 | Minimal module registry, released/entitled/activated gates | T05 | Disabled API denied, history preserved | M |
| P01-T07 | Patient registration/self-service verification and duplicate detection | T05 | Authorization, duplicate and idempotency tests | M |
| P01-T08 | Doctor schedule, appointments, concurrent slot booking | T07 | Concurrency/rescheduling tests | M |
| P01-T09 | Reception queue, search, check-in and walk-ins in approved UI | T08 | Playwright role and touch/keyboard tests | M |
| P01-T10 | Integrated regression, staged backup/recovery, customer pilot readiness report | T01–T09 | Executed evidence and release classification | M |

No Phase 01 work can start until Phase 00 owner acceptance and first task approval. Phases 02–14 retain separate phase-gated task breakdown before execution; particularly stylus, Rx, licensing, fertility and mobile require clinical and platform reviews.
