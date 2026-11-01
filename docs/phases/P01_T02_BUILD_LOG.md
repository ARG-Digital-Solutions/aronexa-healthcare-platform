# P01-T02 — PostgreSQL / Flyway isolated foundation build log
Status: READY FOR REVIEW (not merged or approved)
Branch: `feature/p01-t02-postgres-flyway-isolation`
PR: https://github.com/ARG-Digital-Solutions/aronexa-healthcare-platform/pull/11
Verification: GitHub Actions `PR Quality Gates` run **41344738233**, at 2026-11-01 20:02 UTC (2026-11-02 IST).
https://github.com/ARG-Digital-Solutions/aronexa-healthcare-platform/actions/runs/41344738233

## Scope and changes
- Added Spring Boot JDBC, PostgreSQL driver, Flyway PostgreSQL module and Testcontainers test dependencies (existing Boot/Modulith versions unchanged).
- Explicit `application-dev.yml` datasource with environment-supplied password; isolated test profile with ephemeral PostgreSQL.
- Flyway V1 initializes only `platform_schema_metadata`, a nonclinical bootstrap table; no patient, tenant, role or clinical tables.
- Set `clean-disabled=true`, `baseline-on-migrate=false`, `validate-on-migrate=true`; versioned migration must remain immutable once applied.
- Dev-only Compose PostgreSQL container bound to 127.0.0.1:5433, with persistent named volume and uncommitted local password.
- CI validates Docker Compose syntax, Docker availability, Maven tests and locked npm frontend build.

## Actual verification evidence
| Command / check | Result | Evidence |
|---|---|---|
| `docker compose -f deploy/dev/compose.yml config --quiet` (CI placeholder env) | PASS | Application CI job: local Compose syntax validation |
| `docker info` | PASS | Application CI job: Docker available on GitHub runner |
| `mvn -B -f backend/pom.xml verify` | PASS | Maven BUILD SUCCESS; 5 JUnit tests, 0 failures/errors/skips |
| PostgreSQL 17 / Flyway clean migration | PASS | Testcontainers started database `aronexa_test`; Flyway successfully applied V1 |
| Flyway repeat migration | PASS | Logs: schema version 1, no migration necessary |
| Flyway clean-disabled configuration | PASS | JUnit `destructiveFlywayCleanIsDisabled` (non-destructive assertion) |
| Modulith boundary verification | PASS | ArchitectureTests: 1 test |
| `npm ci --no-audit --no-fund && npm run build` | PASS | TypeScript/Vite production build succeeded |
| Documentation validation | PASS | GitHub Actions documentation job |
| PR scope check | PASS | GitHub Actions scope job |
| Limited tracked-secret check | PASS | GitHub Actions secret patterns job |

## Not verified / deferred
- Local developer's Compose database **was not started** or smoke-tested manually (only syntax checked).
- Production or customer database migration, production backup/restore and deployment: NOT RUN / OUT OF SCOPE.
- PostgreSQL tenant RLS, multi-tenant schema, branch and object isolation: NOT IMPLEMENTED (P01-T03/P01-T05).
- Dedicated application-vs-migration DB roles: NOT IMPLEMENTED (future separately approved task).
- Clinical application and patient data tests: NOT APPLICABLE (no clinical domain yet).
- No real secrets or patient data introduced.

## Risk / review gates
- Flyway V1 must not be edited after application; subsequent schema changes require additive forward-only migrations.
- Existing GitHub ruleset is configured but GitHub plan does not technically enforce PR status checks; review PR diff and latest CI result manually.
- A successful GitHub test does not prove local developer environment is installed correctly.
- This PR must not be merged or deployed without owner approval; P01-T03 is not authorized.
