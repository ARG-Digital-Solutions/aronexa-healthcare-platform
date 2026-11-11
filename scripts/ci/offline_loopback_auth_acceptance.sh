#!/usr/bin/env bash
# COMM-V1-T13: GitHub CI ONLY. Creates its own PG17; never touches Windows DB.
set -Eeuo pipefail
umask 077
[[ "${GITHUB_ACTIONS:-}" == "true" && "${CI:-}" == "true" && -n "${GITHUB_RUN_ID:-}" ]] || {
  echo "REFUSED: use a disposable GitHub Actions runner only" >&2; exit 2;
}
for executable in docker java mvn python3 openssl curl ss; do
  command -v "$executable" >/dev/null || { echo "Missing CI tool: $executable" >&2; exit 2; }
done
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)"
[[ "$ROOT" == "$(cd "${GITHUB_WORKSPACE:?}" && pwd -P)" ]] || exit 2
[[ -f "$ROOT/frontend/dist/index.html" ]] || { echo "Compile frontend before run"; exit 2; }
WORK="$(mktemp -d -p "${RUNNER_TEMP:-/tmp}" aronexa-t13-XXXXXX)"
PG_NAME="aronexa-t13-${GITHUB_RUN_ID}-$(openssl rand -hex 4)"
OWNER_PASS="$(openssl rand -hex 24)"
APP_PASS="$(openssl rand -hex 24)"
AUTH_PASS="$(openssl rand -hex 24)"
PATIENT_PASS="$(openssl rand -hex 24)"
for item in "$OWNER_PASS" "$APP_PASS" "$AUTH_PASS" "$PATIENT_PASS"; do echo "::add-mask::$item"; done
APP_PID=""; UI_PID=""
cleanup() {
  [[ -z "$UI_PID" ]] || { kill "$UI_PID" 2>/dev/null || true; wait "$UI_PID" 2>/dev/null || true; }
  [[ -z "$APP_PID" ]] || { kill "$APP_PID" 2>/dev/null || true; wait "$APP_PID" 2>/dev/null || true; }
  docker rm -fv "$PG_NAME" >/dev/null 2>&1 || true
  rm -rf -- "$WORK"
}
trap cleanup EXIT
docker info >/dev/null
docker run -d --name "$PG_NAME" -p 127.0.0.1::5432 \
  --label aronexa.test-only=T13 \
  -e POSTGRES_DB=aronexa_t13_ci -e POSTGRES_USER=aronexa_ci_migrator \
  -e "POSTGRES_PASSWORD=$OWNER_PASS" \
  -e "ARONEXA_RUNTIME_DB_PASSWORD=$APP_PASS" \
  -e "ARONEXA_AUTH_DB_PASSWORD=$AUTH_PASS" \
  -e "ARONEXA_PATIENT_DB_PASSWORD=$PATIENT_PASS" \
  -v "$ROOT/deploy/dev/initdb/10-create-runtime-role.sh:/docker-entrypoint-initdb.d/10-create-runtime-role.sh:ro" \
  postgres:17-alpine >/dev/null
READY=0
for _ in $(seq 1 70); do
  if docker exec "$PG_NAME" pg_isready -q -U aronexa_ci_migrator -d aronexa_t13_ci; then
    READY=1; break
  fi
  sleep 1
done
[[ "$READY" == "1" ]] || { echo "Fresh database failed"; exit 1; }
PG_PORT="$(docker port "$PG_NAME" 5432/tcp | sed -nE 's/^127[.]0[.]0[.]1:([0-9]+)$/\1/p')"
[[ "$PG_PORT" =~ ^[0-9]+$ ]] || { echo "Database listener must bind IPv4 loopback"; exit 1; }
DB_COUNT="$(docker exec "$PG_NAME" psql -X -At -U aronexa_ci_migrator -d aronexa_t13_ci -c "
SELECT count(*) FROM pg_roles WHERE rolname IN
('aronexa_app_runtime','aronexa_auth_runtime','aronexa_patient_runtime')
AND NOT rolsuper AND NOT rolbypassrls;")"
[[ "$DB_COUNT" == "3" ]] || { echo "Runtime DB role separation failed"; exit 1; }

mvn -B -f "$ROOT/backend/pom.xml" -DskipTests package >"$WORK/maven.log" 2>&1 || {
  echo "Backend package failed" >&2; tail -n 35 "$WORK/maven.log" >&2; exit 1;
}
BACKEND="$ROOT/backend/target/aronexa-backend-0.1.0-SNAPSHOT.jar"
[[ -s "$BACKEND" ]] || { echo "Backend jar missing"; exit 1; }
pick_port() { python3 -c 'import socket;s=socket.socket();s.bind(("127.0.0.1",0));print(s.getsockname()[1]);s.close()'; }
API_PORT="$(pick_port)"
UI_PORT="$(pick_port)"
[[ "$API_PORT" != "$UI_PORT" ]] || { echo "Port collision"; exit 1; }
export ARONEXA_DB_URL="jdbc:postgresql://127.0.0.1:$PG_PORT/aronexa_t13_ci"
export ARONEXA_AUTH_DB_URL="$ARONEXA_DB_URL"
export ARONEXA_PATIENT_DB_URL="$ARONEXA_DB_URL"
export ARONEXA_DB_USERNAME=aronexa_ci_migrator
export ARONEXA_DB_PASSWORD="$OWNER_PASS"
export ARONEXA_RUNTIME_DB_PASSWORD="$APP_PASS"
export ARONEXA_AUTH_DB_PASSWORD="$AUTH_PASS"
export ARONEXA_PATIENT_DB_PASSWORD="$PATIENT_PASS"
java -jar "$BACKEND" --spring.profiles.active=dev \
  --server.address=127.0.0.1 --server.port="$API_PORT" >"$WORK/backend.log" 2>&1 &
APP_PID="$!"
API="http://127.0.0.1:$API_PORT"
READY=0
for _ in $(seq 1 100); do
  kill -0 "$APP_PID" 2>/dev/null || { echo "Spring exited"; tail -n 70 "$WORK/backend.log"; exit 1; }
  status="$(curl -s -o /dev/null -w '%{http_code}' --max-time 2 "$API/api/v1/auth/me" || true)"
  if [[ "$status" == 401 ]]; then READY=1; break; fi
  sleep 2
done
[[ "$READY" == 1 ]] || { echo "Auth startup failed"; tail -n 70 "$WORK/backend.log"; exit 1; }
SOCKETS="$(ss -ltnH "( sport = :$API_PORT )")"
# Java on Linux can expose the *same IPv4-only* loopback bind as
# [::ffff:127.0.0.1]:port. Accept both while rejecting 0.0.0.0/::/*.
if ! grep -Eq "(^|[[:space:]])(\\[::ffff:)?127[.]0[.]0[.]1\\]?:$API_PORT([[:space:]]|$)" <<< "$SOCKETS"; then
  echo "FAIL: expected exclusively IPv4 loopback listener" >&2
  echo "Observed socket (no secrets): $SOCKETS" >&2
  exit 1
fi
if grep -Eq "(0[.]0[.]0[.]0|\\[::\\]|\\*):$API_PORT([[:space:]]|$)" <<< "$SOCKETS"; then
  echo "FAIL: externally reachable/wildcard listener" >&2; exit 1
fi
echo "PASS: actual Spring Boot startup on loopback with disposable PG17 V9"

# Bcrypt fixture encodes PUBLIC SYNTHETIC password OfflineSynthetic_123!
# No user-created credential, clinical record or patient master row is used.
SFX="$(openssl rand -hex 4)"
TENANT_ID="$(python3 -c 'import uuid;print(uuid.uuid4())')"
USER_ID="$(python3 -c 'import uuid;print(uuid.uuid4())')"
MEMBER_ID="$(python3 -c 'import uuid;print(uuid.uuid4())')"
ROLE_ID="$(python3 -c 'import uuid;print(uuid.uuid4())')"
TENANT_CODE="synt13_$SFX"
USERNAME="user13_$SFX"
HASH='$2y$12$3re8bOADBf9ZXtuzyP2Lwe3kCHLr7Au0yg3oOxW7yVs9Gu4odUFOm'
docker exec -i "$PG_NAME" psql -X -q -v ON_ERROR_STOP=1 -U aronexa_ci_migrator \
  -d aronexa_t13_ci -v tenant="$TENANT_ID" -v user_id="$USER_ID" \
  -v member="$MEMBER_ID" -v role="$ROLE_ID" -v code="$TENANT_CODE" \
  -v username="$USERNAME" -v hash="$HASH" <<'SQL'
INSERT INTO public.tenant(id,code,display_name)
VALUES (:'tenant'::uuid,:'code','SYNTHETIC CI TENANT');
INSERT INTO public.app_user(id,username,password_hash)
VALUES (:'user_id'::uuid,:'username',:'hash');
INSERT INTO public.user_tenant_membership(id,user_id,tenant_id)
VALUES (:'member'::uuid,:'user_id'::uuid,:'tenant'::uuid);
INSERT INTO public.auth_role(id,tenant_id,code,display_name)
VALUES (:'role'::uuid,:'tenant'::uuid,'RECEPTION','SYNTHETIC RECEPTION');
INSERT INTO public.auth_permission(code,description)
VALUES ('ORG_READ','Synthetic CI permission') ON CONFLICT (code) DO NOTHING;
INSERT INTO public.auth_role_permission(tenant_id,role_id,permission_code)
VALUES (:'tenant'::uuid,:'role'::uuid,'ORG_READ');
INSERT INTO public.auth_membership_role(tenant_id,membership_id,role_id)
VALUES (:'tenant'::uuid,:'member'::uuid,:'role'::uuid);
SQL

http_status() { curl -sS -o "$WORK/last.json" -w '%{http_code}' --max-time 5 "$@"; }
[[ "$(http_status -H 'Content-Type: application/json' -d \
 '{"tenantCode":"missing","username":"unknown","password":"fake"}' "$API/api/v1/auth/login")" == 401 ]] || {
  echo "Invalid login was not rejected"; exit 1;
}
python3 - "$WORK/login.json" "$TENANT_CODE" "$USERNAME" <<'PY'
import json,sys
with open(sys.argv[1],"w") as f:
 json.dump({"tenantCode":sys.argv[2],"username":sys.argv[3],
            "password":"OfflineSynthetic_123!"},f)
PY
[[ "$(http_status -H 'Content-Type: application/json' --data-binary @"$WORK/login.json" \
 "$API/api/v1/auth/login")" == 200 ]] || { echo "Synthetic login failed"; exit 1; }
TOKEN="$(python3 - "$WORK/last.json" <<'PY'
import json,sys
j=json.load(open(sys.argv[1]));assert j["tokenType"]=="Bearer"
assert j["mustChangePassword"] is True
assert len(j["accessToken"])==43
print(j["accessToken"])
PY
)"
echo "::add-mask::$TOKEN"
[[ "$(http_status -H "Authorization: Bearer $TOKEN" \
  -H "X-Tenant-Id: 00000000-0000-0000-0000-000000000001" \
 "$API/api/v1/auth/me")" == 200 ]] || { echo "Me request failed"; exit 1; }
python3 - "$WORK/last.json" "$TENANT_ID" "$MEMBER_ID" <<'PY'
import json,sys
j=json.load(open(sys.argv[1]))
assert j["tenantId"]==sys.argv[2], "Forged tenant request header changed identity"
assert j["membershipId"]==sys.argv[3]
assert j["roles"]==["RECEPTION"]
assert "ORG_READ" in j["permissions"]
PY
[[ "$(http_status -H "Authorization: Bearer $TOKEN" \
 "$API/api/v1/clinical/consultation")" == 403 ]] || {
 echo "Unknown clinical API was not denied"; exit 1;
}
curl -sS --max-time 5 -D "$WORK/cors.txt" -o /dev/null \
 -H "Origin: https://untrusted.invalid" -H "Authorization: Bearer $TOKEN" \
 "$API/api/v1/auth/me"
! grep -iq '^access-control-allow-origin:' "$WORK/cors.txt" || {
 echo "Authenticated identity exposed CORS"; exit 1;
}
echo "PASS: valid login, scoped identity, forged header denial, clinical route denial"

python3 - "$WORK/change.json" <<'PY'
import json,sys
with open(sys.argv[1],"w") as f:
 json.dump({"currentPassword":"OfflineSynthetic_123!",
            "newPassword":"OfflineSynthetic_New_456!"},f)
PY
[[ "$(http_status -X POST -H "Authorization: Bearer $TOKEN" \
 -H 'Content-Type: application/json' --data-binary @"$WORK/change.json" \
 "$API/api/v1/auth/password")" == 204 ]] || { echo "Password change failed"; exit 1; }
[[ "$(http_status -H "Authorization: Bearer $TOKEN" "$API/api/v1/auth/me")" == 401 ]] || {
 echo "Old token not revoked after password change"; exit 1;
}
python3 - "$WORK/login-new.json" "$TENANT_CODE" "$USERNAME" <<'PY'
import json,sys
with open(sys.argv[1],"w") as f:
 json.dump({"tenantCode":sys.argv[2],"username":sys.argv[3],
           "password":"OfflineSynthetic_New_456!"},f)
PY
[[ "$(http_status -H 'Content-Type: application/json' --data-binary @"$WORK/login-new.json" \
 "$API/api/v1/auth/login")" == 200 ]] || { echo "Re-login failed"; exit 1; }
NEW_TOKEN="$(python3 - "$WORK/last.json" <<'PY'
import json,sys
j=json.load(open(sys.argv[1]));assert j["mustChangePassword"] is False
print(j["accessToken"])
PY
)"
echo "::add-mask::$NEW_TOKEN"
[[ "$(http_status -X POST -H "Authorization: Bearer $NEW_TOKEN" \
 "$API/api/v1/auth/logout")" == 204 ]] || { echo "Logout failed"; exit 1; }
[[ "$(http_status -H "Authorization: Bearer $NEW_TOKEN" "$API/api/v1/auth/me")" == 401 ]] || {
 echo "Logout did not revoke token"; exit 1;
}
echo "PASS: password change revokes old token, re-login and logout revocation"

VERSION="$(docker exec "$PG_NAME" psql -X -At -U aronexa_ci_migrator -d aronexa_t13_ci \
 -c 'SELECT version FROM public.flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1')"
PATIENT_COUNT="$(docker exec "$PG_NAME" psql -X -At -U aronexa_ci_migrator -d aronexa_t13_ci \
 -c 'SELECT count(*) FROM public.patient')"
[[ "$VERSION" == 9 && "$PATIENT_COUNT" == 0 ]] || {
 echo "Unexpected migration version or patient identity fixture"; exit 1;
}
echo "PASS: V9 applied to new CI DB only; patient master remains empty"

python3 -m http.server "$UI_PORT" --bind 127.0.0.1 \
  --directory "$ROOT/frontend/dist" >"$WORK/ui.log" 2>&1 &
UI_PID="$!"
UI_READY=0
for _ in $(seq 1 20); do
  CODE="$(curl -s -w '%{http_code}' -o "$WORK/index.html" --max-time 2 \
  "http://127.0.0.1:$UI_PORT/" || true)"
  if [[ "$CODE" == 200 ]] && grep -qi aronexa "$WORK/index.html"; then UI_READY=1; break; fi
  sleep 1
done
[[ "$UI_READY" == 1 ]] || { echo "Compiled React scaffold UI missing"; exit 1; }
echo "PASS: React foundation visible on a separate loopback-only static preview"
echo "COMPLETE: synthetic CI-only Spring Boot authentication + fresh PG17 + scaffold."
echo "NOT YET: production clinical API, full OPD, Windows service, installer, recovery/UAT."
