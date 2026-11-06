#!/usr/bin/env bash
# Acceptance smoke against a FRESH CI Docker volume only.
# This script must NOT be used on an existing customer or developer volume.
set -euo pipefail

if [[ -z "${GITHUB_ACTIONS:-}" ]]; then
  echo "REFUSED: this script is CI-only. Use docs/phases/P01_SECURITY_CLOSURE_ACCEPTANCE.md for local read-only preflight." >&2
  exit 2
fi
for key in ARONEXA_DB_PASSWORD ARONEXA_RUNTIME_DB_PASSWORD ARONEXA_AUTH_DB_PASSWORD ARONEXA_PATIENT_DB_PASSWORD; do
  if [[ -z "${!key:-}" ]]; then echo "Missing required CI credential: $key" >&2; exit 2; fi
done

cd "$(dirname "$0")/../.."
PROJECT="aronexa-acceptance-${GITHUB_RUN_ID:-manual}"
COMPOSE=(docker compose -p "$PROJECT" -f deploy/dev/compose.yml)
LOG="${RUNNER_TEMP:-/tmp}/aronexa-dev-runtime-${GITHUB_RUN_ID:-manual}.log"
MAVEN_PID=""
cleanup() {
  if [[ -n "$MAVEN_PID" ]]; then
    # Spring Boot Maven plugin may create child processes. Kill Maven and
    # any remaining children in this ephemeral runner.
    pkill -P "$MAVEN_PID" 2>/dev/null || true
    kill "$MAVEN_PID" 2>/dev/null || true
    wait "$MAVEN_PID" 2>/dev/null || true
  fi
  "${COMPOSE[@]}" down --remove-orphans >/dev/null 2>&1 || true
}
trap cleanup EXIT

docker info >/dev/null
"${COMPOSE[@]}" config --quiet
"${COMPOSE[@]}" up -d --wait --wait-timeout 120

# These identities MUST have been provisioned during fresh initdb.
roles="$("${COMPOSE[@]}" exec -T postgres psql -X -At -U aronexa_dev -d aronexa_dev -c "
  SELECT count(*) FROM pg_catalog.pg_roles
  WHERE rolname IN ('aronexa_app_runtime','aronexa_auth_runtime','aronexa_patient_runtime');
")"
if [[ "$roles" != "3" ]]; then
  echo "FAIL: fresh PostgreSQL did not bootstrap all three restricted roles." >&2
  exit 1
fi
echo "PASS: isolated PostgreSQL and restricted role creation"

# Run the REAL dev Spring profile: not the test profile used by Testcontainers.
mvn -B -f backend/pom.xml spring-boot:run "-Dspring-boot.run.profiles=dev" >"$LOG" 2>&1 &
MAVEN_PID="$!"
READY=0
for _ in $(seq 1 90); do
  if ! kill -0 "$MAVEN_PID" 2>/dev/null; then
    echo "FAIL: Spring Boot process exited. Last 120 log lines:" >&2
    tail -n 120 "$LOG" >&2
    exit 1
  fi
  status="$(curl --silent --output /dev/null --write-out '%{http_code}' --max-time 2 http://127.0.0.1:8080/api/v1/auth/me || true)"
  if [[ "$status" == "401" ]]; then READY=1; break; fi
  sleep 2
done
if [[ "$READY" != "1" ]]; then
  echo "FAIL: unauthenticated /api/v1/auth/me did not return 401. Startup log:" >&2
  tail -n 120 "$LOG" >&2
  exit 1
fi
echo "PASS: Spring Boot dev profile started; unauthenticated access is denied"

version="$("${COMPOSE[@]}" exec -T postgres psql -X -At -U aronexa_dev -d aronexa_dev -c "
  SELECT version FROM public.flyway_schema_history
  WHERE success ORDER BY installed_rank DESC LIMIT 1;
")"
if [[ "$version" != "9" ]]; then
  echo "FAIL: unexpected Flyway version (wanted 9)." >&2; exit 1
fi
echo "PASS: Flyway V1-V9 applied"

# Check role privileges without exposing credentials or data.
org_auth_select="$("${COMPOSE[@]}" exec -T -e "PGPASSWORD=$ARONEXA_RUNTIME_DB_PASSWORD" postgres \
  psql -X -At -h 127.0.0.1 -U aronexa_app_runtime -d aronexa_dev -c \
  "SELECT has_table_privilege(current_user, 'public.app_user', 'SELECT');")"
auth_org_select="$("${COMPOSE[@]}" exec -T -e "PGPASSWORD=$ARONEXA_AUTH_DB_PASSWORD" postgres \
  psql -X -At -h 127.0.0.1 -U aronexa_auth_runtime -d aronexa_dev -c \
  "SELECT has_table_privilege(current_user, 'public.branch', 'SELECT');")"
if [[ "$org_auth_select" != "f" || "$auth_org_select" != "f" ]]; then
  echo "FAIL: unexpected cross-role table permissions." >&2; exit 1
fi
echo "PASS: organization and authentication database roles separated"

# V8 foundation is deliberately not usable by either runtime identity yet.
for table in patient patient_identifier patient_contact patient_relationship patient_registration patient_identity_audit; do
  for role in aronexa_app_runtime aronexa_auth_runtime; do
    grant="$("${COMPOSE[@]}" exec -T postgres psql -X -At -U aronexa_dev -d aronexa_dev -c "
      SELECT has_table_privilege('$role', 'public.$table', 'SELECT');")"
    if [[ "$grant" != "f" ]]; then
      echo "FAIL: premature patient table access for $role on $table" >&2
      exit 1
    fi
  done
done
echo "PASS: patient identity tables deny app/auth runtime roles"

# The patient runtime gets scoped SELECT of branch registration IDs, and
# INSERT-only access to audit rows; never blanket master identity access.
for table in patient patient_identifier patient_contact patient_relationship patient_identity_audit; do
  access="$("${COMPOSE[@]}" exec -T postgres psql -X -At -U aronexa_dev -d aronexa_dev -c "
    SELECT has_table_privilege('aronexa_patient_runtime', 'public.$table', 'SELECT');")"
  if [[ "$access" != "f" ]]; then
    echo "FAIL: patient runtime can see restricted $table" >&2; exit 1
  fi
done
patient_read="$("${COMPOSE[@]}" exec -T postgres psql -X -At -U aronexa_dev -d aronexa_dev -c "
  SELECT has_table_privilege('aronexa_patient_runtime','public.patient_registration','SELECT');")"
patient_write="$("${COMPOSE[@]}" exec -T postgres psql -X -At -U aronexa_dev -d aronexa_dev -c "
  SELECT has_table_privilege('aronexa_patient_runtime','public.patient_registration','INSERT');")"
patient_audit="$("${COMPOSE[@]}" exec -T postgres psql -X -At -U aronexa_dev -d aronexa_dev -c "
  SELECT has_table_privilege('aronexa_patient_runtime','public.patient_registration_access_audit','INSERT');")"
if [[ "$patient_read" != "t" || "$patient_write" != "f" || "$patient_audit" != "t" ]]; then
  echo "FAIL: patient runtime privilege boundary is incorrect" >&2; exit 1
fi
echo "PASS: patient role has bounded read and append-only audit write privileges"

invalid_login="$(curl --silent --output /dev/null --write-out '%{http_code}' --max-time 5 \
  -X POST http://127.0.0.1:8080/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  --data '{"tenantCode":"missing","username":"unknown","password":"invalid-test-value"}' || true)"
if [[ "$invalid_login" != "401" ]]; then
  echo "FAIL: invalid synthetic login returned $invalid_login (expected 401)." >&2
  tail -n 100 "$LOG" >&2; exit 1
fi
echo "PASS: invalid login rejected without account enumeration"
echo "PASS: fresh isolated dev-profile acceptance smoke (no patient data)"
