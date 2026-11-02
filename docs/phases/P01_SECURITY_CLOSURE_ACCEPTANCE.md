# Phase 01 — Security Closure and Local Runtime Acceptance

**Status:** CONDITIONAL ENGINEERING FOUNDATION; local Windows acceptance OPEN, clinical/production acceptance BLOCKED.
**Basis:** Merged P01-T01 through P01-T05-D, Flyway V1–V6, last successful PR #17 CI run [41432035904](https://github.com/ARG-Digital-Solutions/aronexa-healthcare-platform/actions/runs/41432035904), 53/53 Java tests and four passing CI jobs. Those tested a Testcontainers `test` profile, NOT the real `dev` profile.

## Evidence and acceptance matrix

| Gate | Finding | Result |
|---|---|---|
| P01-T01–T05-D source merged | PR #17 and V6, authorization classes, tests on main | PASS — merged foundation |
| Authentication + PostgreSQL + RLS regressions | 53/53 Java tests in PR #17 | PASS — test profile |
| Frontend reproducible build | npm ci and production build in PR #17 | PASS |
| Independent runtime/auth database roles | V6 SQL and restricted-role tests | PASS within test fixtures |
| Fresh three-role dev startup | New CI workflow `Dev Runtime Acceptance` | PENDING latest CI result |
| Windows Docker Desktop and backend startup | No access to user's Windows computer | NOT VERIFIED |
| Existing development database migration from V5 to V6 | No existing-volume smoke evidence | NOT VERIFIED |
| Real browser sign-in | No login UI or seeded accounts | NOT IMPLEMENTED |
| Doctor/patient/encounter authorization | No clinical entities yet | NOT IMPLEMENTED |
| Tenant-safe auth data | Auth DB identity still has broad account/session privileges | SECURITY BLOCKER |
| MFA, recovery, audit/compliance, backup/restore | Incomplete | NOT ACCEPTED |
| Production or live patient data | Never authorized | PROHIBITED |

## Windows acceptance — safe developer runbook

### 1. Read-only prerequisites
Run these from the repository folder, not its parent:

```powershell
git rev-parse --show-toplevel
git status --short
git switch main
git pull --ff-only origin main
java -version
mvn --version
node --version
docker info
```

If Git says "not a git repository", navigate into the actual `aronexa-healthcare-platform` clone. Do NOT run `git init` in an arbitrary directory. Check for uncommitted changes before switching branches.

### 2. Development-only credentials
Copy `deploy/dev/.env.example` to the untracked `deploy/dev/.env`, replace the three placeholders with strong DIFFERENT local development passwords. Never commit or share those values.

```powershell
if (-not (Test-Path .\deploy\dev\.env)) {
  Copy-Item .\deploy\dev\.env.example .\deploy\dev\.env
}
notepad .\deploy\dev\.env
$required = @('ARONEXA_DB_PASSWORD','ARONEXA_RUNTIME_DB_PASSWORD','ARONEXA_AUTH_DB_PASSWORD')
$values = @{}
Get-Content .\deploy\dev\.env | ForEach-Object {
  if ($_ -match '^\s*(ARONEXA_DB_PASSWORD|ARONEXA_RUNTIME_DB_PASSWORD|ARONEXA_AUTH_DB_PASSWORD)=(.*)$') {
    $values[$matches[1]] = $matches[2].Trim()
  }
}
foreach ($key in $required) {
  if (-not $values.ContainsKey($key) -or $values[$key].Length -lt 16 -or $values[$key].StartsWith('replace-with')) {
    throw "Missing/placeholder/short credential: $key"
  }
}
if (@($values.Values | Select-Object -Unique).Count -ne 3) {
  throw 'Use three distinct local passwords'
}
foreach ($key in $required) {
  [Environment]::SetEnvironmentVariable($key,$values[$key],'Process')
}
docker compose --env-file .\deploy\dev\.env -f .\deploy\dev\compose.yml config --quiet
```

### 3. Start PostgreSQL, then inspect roles BEFORE Flyway
Never run these commands against a customer database.

```powershell
docker compose --env-file .\deploy\dev\.env -f .\deploy\dev\compose.yml up -d --wait
docker compose --env-file .\deploy\dev\.env -f .\deploy\dev\compose.yml exec -T postgres psql -X -At -U aronexa_dev -d aronexa_dev -c "SELECT rolname FROM pg_roles WHERE rolname IN ('aronexa_app_runtime','aronexa_auth_runtime') ORDER BY rolname;"
```

Expect exactly `aronexa_app_runtime` and `aronexa_auth_runtime`. A previously created Docker volume does NOT rerun the bootstrap scripts. If either role is missing, STOP and request a separate approved non-destructive role-provisioning procedure. Do not remove volumes, alter roles manually, or run Flyway clean.

For an existing database, save the output of this **read-only** SQL before starting the backend (the history table may be absent in a fresh DB):

```powershell
docker compose --env-file .\deploy\dev\.env -f .\deploy\dev\compose.yml exec -T postgres psql -X -U aronexa_dev -d aronexa_dev -c "SELECT installed_rank, version, description, success FROM public.flyway_schema_history ORDER BY installed_rank;"
```

Back up and verify any nonempty development DB before allowing migrations. Do not start Maven until role preflight and data preservation are satisfactory.

### 4. Actual dev-profile backend startup
Maven Spring Boot will use migration-owner, organization-runtime, and authentication-runtime credentials. It can apply pending Flyway migrations, so only proceed on your safe isolated development database.

```powershell
mvn -B -f .\backend\pom.xml spring-boot:run '-Dspring-boot.run.profiles=dev'
```

In another terminal:

```powershell
curl.exe -i http://127.0.0.1:8080/api/v1/auth/me
curl.exe -i -X POST http://127.0.0.1:8080/api/v1/auth/login -H "Content-Type: application/json" --data '{"tenantCode":"missing","username":"unknown","password":"invalid-test-value"}'
```

Both must return HTTP **401**. Startup must show Flyway V6 or "schema already up to date"; no SQL/permission failures. No user or administrator is seeded, so valid interactive login is not available without an approved dev-only test account provisioning workflow.

### 5. Frontend and regression
From the repository root, in additional terminals:

```powershell
cd frontend
npm ci
npm run build
npm run dev
```

Visit http://127.0.0.1:5173. Expect the **foundation scaffold only**, not a login form or patient dashboard.

```powershell
mvn -B -f .\backend\pom.xml verify
```

The backend integration tests create their own ephemeral PostgreSQL Testcontainers. Stop Maven/Vite with Ctrl+C and preserve DB data using:

```powershell
docker compose --env-file .\deploy\dev\.env -f .\deploy\dev\compose.yml stop
```

Never run `down -v` or destructive migrations to fix a development failure.

### 6. Evidence and owner signoff
Record only: tool versions, sanitized Docker/role **names**, schema version, backend started, HTTP status codes, test summary, frontend screenshot. Do NOT share `.env`, passwords, bearer tokens, raw production logs or any patient information. Final Windows PASS requires actual observed local startup and owner acceptance, not inference from Linux CI.

## Fresh CI acceptance (separate from Windows)
The new `Dev Runtime Acceptance (Fresh Database)` workflow provisions a fresh isolated Docker Compose PostgreSQL with three generated temporary credentials, runs the normal `dev` Spring profile, verifies Flyway V6, rejects unauthenticated and invalid-login HTTP access, and checks cross-role table permissions. Its script is CI-only and never targets a developer's persistent volume. It does NOT prove Windows setup, upgrades to an existing volume, valid browser sign-in, or clinical authorization.

## Security blockers and release decision
The dedicated authentication database role still has global SQL grants over accounts and sessions; physical role separation reduces attack surface but not all cross-tenant auth data risks. Doctor, patient and encounter ownership/IDOR policies require future domain models. MFA, verified recovery, role provisioning, abuse throttling, independent security assessment, backup/restore, and clinical compliance review remain outstanding.

**Closure recommendation:** phase 01 code/CI foundation can be recorded as merged, but do not certify local Windows acceptance or production/clinical readiness until evidence exists and the owner approves it. No merge, deployment or next phase authorization is implied by this review.
