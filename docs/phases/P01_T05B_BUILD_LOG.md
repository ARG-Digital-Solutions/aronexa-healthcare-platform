# P01-T05-B — Database RLS and least-privileged runtime role

**State:** IN PROGRESS; draft PR and CI evidence to be recorded.
**Release classification:** INTERNAL_FOUNDATION — NOT READY.
**Task:** Dedicated migration/runtime credentials, RLS for organization hierarchy, transaction-scoped verified tenant and PostgreSQL isolation checks.

## Scope
- Additive Flyway V4 RLS policies and GRANTs; V1–V3 unchanged.
- Runtime/migration credential split for local dev, isolated runtime role bootstrap on first Docker volume creation.
- Testcontainers provisioning of a genuine non-owner runtime database role.
- Parameterized transaction-local GUC binding based on authenticated identity.
- Pre-login tenant lookup via strictly limited SECURITY DEFINER function.
- Tests for unscoped/wrong-tenant SELECT/UPDATE/DELETE/INSERT, malformed context, and pooled connection reuse.

## Verification pending
| Command / gate | Status |
|---|---|
| GitHub Actions documentation/scope/basic secrets | NOT RUN |
| `docker compose -f deploy/dev/compose.yml config --quiet` | NOT RUN |
| `mvn -B -f backend/pom.xml verify` (Flyway V4 + all tests) | NOT RUN |
| Runtime PostgreSQL role non-owner / non-BYPASSRLS | NOT RUN |
| No-context / cross-tenant DML rejection | NOT RUN |
| Pooled connection transaction context clearing | NOT RUN |
| HTTP authentication regression | NOT RUN |
| `npm ci --no-audit --no-fund && npm run build` | NOT RUN |

## Critical limitation
Authentication schema tables still grant runtime SQL access before tenant context exists. They are NOT RLS-isolated under this milestone. No claim of end-to-end healthcare or clinical tenant isolation. Future T05-C/D, authentication privilege partitioning and production security review are mandatory. No deployment, customer data migration, merge or next subtask without approval.
