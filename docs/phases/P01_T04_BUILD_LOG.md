# P01-T04 — Authentication lifecycle and RBAC foundation
Status: IN PROGRESS (CI evidence to follow).
Branch: `feature/p01-t04-authentication-foundation`.
Scope: V3 authentication tables, secure password hashes, revocable sessions, basic tenant roles/permission model, API endpoints, PostgreSQL integration tests.

## Verification targets
- Maven verify with Testcontainers, Flyway V1/V2/V3, Spring Modulith tests: NOT RUN.
- HTTP login/me/logout/password-change, invalid token and account lockout cases: NOT RUN.
- Cross-tenant role FK, disabled membership tests: NOT RUN.
- npm ci and Vite build: NOT RUN.
- CI documentation, PR scope, tracked secrets: NOT RUN.

## Deferred
P01-T05 clinical/resource RBAC & RLS; self-service onboarding, staff approval, full incident response, MFA, password recovery, platform bootstrap and distributed anti-abuse controls.
No merge, deploy or next task without owner authorization. INTERNAL_FOUNDATION only.
