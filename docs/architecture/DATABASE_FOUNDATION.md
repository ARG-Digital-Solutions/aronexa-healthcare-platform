# P01-T02 — PostgreSQL/Flyway environment isolation and migration contract

## Scope and environments
- No patient, tenant, role or clinical schema is created by P01-T02.
- Local dev: opt in with `dev` Spring profile, PostgreSQL 17 from `deploy/dev/compose.yml` on 127.0.0.1:5433, dedicated `aronexa_dev` database; Docker named volume retains development data.
- Automated tests: `test` Spring profile, JUnit + Testcontainers PostgreSQL 17 with ephemeral DB `aronexa_test`; Spring Boot `@ServiceConnection` injects DB connection to JDBC and Flyway. No fallback to developer's local DB and no static credentials outside test container.
- Default Spring profile intentionally contains no connection coordinates: startup with no explicitly configured DB fails closed.
- Cloud/production/offline configurations are *not* implemented. Never point local dev or CI at customer databases.

## Migration rules
- Flyway versioned scripts under `backend/src/main/resources/db/migration`, immutable once applied.
- V1 creates only a nonclinical `platform_schema_metadata` table, and Flyway's own schema history is managed automatically.
- `clean-disabled=true`; `baseline-on-migrate=false`; `validate-on-migrate=true`. Do not edit applied V1 or reset volumes to bypass a failure.
- Every future migration requires forward-only compatibility review, index/lock impact assessment, backup/recovery plan, and tests against a fresh and an upgraded isolated database.
- Separate migration/runtime database roles and transaction-scoped RLS will be designed/implemented in P01-T03/P01-T05, not deceptively claimed as part of this foundation.

## Local developer startup (opt-in)
1. Copy `deploy/dev/.env.example` to `deploy/dev/.env` and replace the placeholder with a strong dev-only password.
2. From repo root: `docker compose --env-file deploy/dev/.env -f deploy/dev/compose.yml up -d --wait`.
3. Export `ARONEXA_DB_PASSWORD` from the **same local .env value** in your terminal (never commit or paste secrets to logs). Run `mvn -B -f backend/pom.xml spring-boot:run -Dspring-boot.run.profiles=dev`.
4. Use `docker compose --env-file deploy/dev/.env -f deploy/dev/compose.yml stop` to stop without deleting data. Do not run `down -v` or Flyway clean on data you want to preserve.
5. To test: ensure Docker Engine is available, then `mvn -B -f backend/pom.xml verify`. Testcontainers handles ephemeral DB creation/destruction.

## Verification gates
- CI checks `docker info`, runs Maven verify (context, Flyway migration, re-run stability, Modulith architecture test), and independently runs locked npm production build.
- Failure to initialize Docker/Postgres must make CI red, never become a skipped or green build.
- P01-T02 deliberately does NOT prove tenant isolation, role security, clinical or production restore workflows.
