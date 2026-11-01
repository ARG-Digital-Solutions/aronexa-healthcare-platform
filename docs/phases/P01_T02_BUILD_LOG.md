# P01-T02 — PostgreSQL / Flyway isolated foundation build log
Status: IN PROGRESS — record GitHub Actions evidence before review.
Branch: feature/p01-t02-postgres-flyway-isolation
Approved scope: DB connection configuration, isolated developer Postgres, minimal V1 metadata migration, Testcontainers migration tests, CI.

## Change summary
- Explicit development datasource profile using environment-supplied password.
- Isolated Testcontainers PostgreSQL with Spring Boot JDBC + Flyway service connection.
- Minimal versioned nonclinical SQL migration and clean-disabled/validate settings.
- Local development-only Docker Compose, named persistent volume and loopback-only port.
- Existing frontend untouched; CI continues reproducible npm build.

## Required evidence
1. CI `docker info`: NOT RUN
2. Maven `mvn -B -f backend/pom.xml verify` incl PostgreSQL/Flyway/Modulith: NOT RUN
3. Frontend `npm ci --no-audit --no-fund && npm run build`: NOT RUN
4. Documentation/scope/secret jobs: NOT RUN
5. Fresh database migration + repeat migration: NOT RUN
6. Migration clean prevention: configuration reviewed; destructive operation NOT RUN
7. Database tenancy, roles, production backups and clinical tests: NOT IN P01-T02; deferred, NOT RUN

No customer/production DB, deployment or data migrations authorized. Do not merge without owner approval. No P01-T03 work.
