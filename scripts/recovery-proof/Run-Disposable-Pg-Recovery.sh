#!/usr/bin/env bash
# COMM-V1-T11: ONLY new, disposable PostgreSQL 17 containers. NO user DB.
set -Eeuo pipefail
umask 077
if [[ "${CI:-}" != "true" && "${ARONEXA_DISPOSABLE_PROOF:-}" != "YES" ]]; then
 echo "Refusing: requires CI or explicit disposable acknowledgement" >&2; exit 2
fi
for tool in docker java javac openssl mktemp python3; do
 command -v "$tool" >/dev/null || { echo "Missing tool $tool" >&2; exit 2; }
done
java --version | head -1 | grep -Eq '(^| )21[.]' || { echo "Requires JDK21"; exit 2; }
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
TMP="$(mktemp -d -t aronexa-PG-synthetic-XXXXXX)"
ID="$(openssl rand -hex 6)"
SOURCE="aronexa-pg-src-$ID"
TARGET="aronexa-pg-dst-$ID"
DB="aronexa_synthetic_proof"
PG_PASS="disposable-$(openssl rand -hex 22)"
RECOVERY_KEY="$(openssl rand -hex 32)"
cleanup() {
 docker rm -f "$SOURCE" "$TARGET" >/dev/null 2>&1 || true
 rm -rf -- "$TMP"
 unset PG_PASS RECOVERY_KEY ARONEXA_RECOVERY_PROOF_PASSWORD
}
trap cleanup EXIT
echo "SYNTHETIC PG17: NEW containers, --network none, no ports or volumes."
for NAME in "$SOURCE" "$TARGET"; do
 docker run --rm -d --name "$NAME" --network none \
   -e "POSTGRES_PASSWORD=$PG_PASS" \
   --label "aronexa.synthetic=COMM-V1-T11" postgres:17-alpine >/dev/null
done
wait_db() {
 local NAME="$1"
 for _ in {1..50}; do
  if docker exec -u postgres "$NAME" pg_isready -q -U postgres; then return; fi
  sleep 1
 done
 echo "Disposable PG failed to start" >&2; exit 3
}
wait_db "$SOURCE"; wait_db "$TARGET"
for NAME in "$SOURCE" "$TARGET"; do
 docker exec -u postgres "$NAME" psql -X -q -v ON_ERROR_STOP=1 \
   -U postgres -d postgres -c \
   "CREATE ROLE aronexa_proof_reader NOLOGIN NOBYPASSRLS;" >/dev/null
 docker exec -u postgres "$NAME" createdb -U postgres "$DB"
done
docker exec -i -u postgres "$SOURCE" psql -X -q -v ON_ERROR_STOP=1 \
  -U postgres -d "$DB" < "$SCRIPT_DIR/fixture.sql" >/dev/null

# pg_dump consistent, plain staging only in private ephemeral runner temp.
docker exec -u postgres "$SOURCE" pg_dump -Fc --no-owner --no-acl \
  -U postgres -d "$DB" > "$TMP/source.pgcustom"
[[ "$(head -c 5 "$TMP/source.pgcustom")" == "PGDMP" ]] || {
 echo "Not a custom pg_dump"; exit 3;
}
javac -encoding UTF-8 -d "$TMP" "$SCRIPT_DIR/DisposablePgArchiveCrypto.java"
export ARONEXA_RECOVERY_PROOF_MODE="DISPOSABLE-CONTAINER-ONLY"
export ARONEXA_RECOVERY_PROOF_PASSWORD="$RECOVERY_KEY"
java -cp "$TMP" DisposablePgArchiveCrypto encrypt "$TMP/source.pgcustom" "$TMP/backup.encrypted"
rm -f -- "$TMP/source.pgcustom"

# Negative cases never create plaintext output.
if ARONEXA_RECOVERY_PROOF_PASSWORD="WRONG-$(openssl rand -hex 22)" \
  java -cp "$TMP" DisposablePgArchiveCrypto decrypt \
  "$TMP/backup.encrypted" "$TMP/wrong.pgcustom" >/dev/null 2>&1; then
 echo "FAIL: wrong password accepted"; exit 4
fi
[[ ! -e "$TMP/wrong.pgcustom" ]] || { echo "FAIL: wrong-key plaintext"; exit 4; }
cp "$TMP/backup.encrypted" "$TMP/tampered.encrypted"
python3 - "$TMP/tampered.encrypted" <<'PY'
import sys
from pathlib import Path
p=Path(sys.argv[1]); d=bytearray(p.read_bytes()); d[-2] ^= 0x40; p.write_bytes(d)
PY
if java -cp "$TMP" DisposablePgArchiveCrypto decrypt \
  "$TMP/tampered.encrypted" "$TMP/tampered.pgcustom" >/dev/null 2>&1; then
 echo "FAIL: tampered ciphertext accepted"; exit 4
fi
[[ ! -e "$TMP/tampered.pgcustom" ]] || { echo "FAIL: tampered plaintext"; exit 4; }
SIZE="$(wc -c < "$TMP/backup.encrypted")"
head -c "$((SIZE - 8))" "$TMP/backup.encrypted" > "$TMP/truncated.encrypted"
if java -cp "$TMP" DisposablePgArchiveCrypto decrypt \
  "$TMP/truncated.encrypted" "$TMP/truncated.pgcustom" >/dev/null 2>&1; then
 echo "FAIL: truncated ciphertext accepted"; exit 4
fi
[[ ! -e "$TMP/truncated.pgcustom" ]] || { echo "FAIL: truncated plaintext"; exit 4; }
echo "Wrong password, tampering and truncation rejected: PASS"

java -cp "$TMP" DisposablePgArchiveCrypto decrypt \
 "$TMP/backup.encrypted" "$TMP/restored.pgcustom"
printf 'KEEP-EXISTING' > "$TMP/do-not-overwrite"
if java -cp "$TMP" DisposablePgArchiveCrypto decrypt \
 "$TMP/backup.encrypted" "$TMP/do-not-overwrite" >/dev/null 2>&1; then
 echo "FAIL: overwrote an existing output"; exit 4
fi
[[ "$(cat "$TMP/do-not-overwrite")" == "KEEP-EXISTING" ]] || exit 4

# Restore into a SECOND instance, never in place.
docker cp "$TMP/restored.pgcustom" "$TARGET:/tmp/aronexa_synthetic.pgcustom"
# docker cp writes as root. Allow only the disposable postgres service user.
docker exec -u root "$TARGET" chown postgres:postgres /tmp/aronexa_synthetic.pgcustom
docker exec -u root "$TARGET" chmod 0600 /tmp/aronexa_synthetic.pgcustom
docker exec -u postgres "$TARGET" pg_restore --exit-on-error --no-owner --no-acl \
 -U postgres -d "$DB" /tmp/aronexa_synthetic.pgcustom >/dev/null
rm -f -- "$TMP/restored.pgcustom"
docker exec -u postgres "$TARGET" rm -f /tmp/aronexa_synthetic.pgcustom
# --no-acl deliberately omits object grants: bootstrap them separately.
docker exec -u postgres "$TARGET" psql -X -q -v ON_ERROR_STOP=1 \
 -U postgres -d "$DB" -c \
 "GRANT USAGE ON SCHEMA public TO aronexa_proof_reader;
 GRANT SELECT ON clinic_patient,clinical_encounter TO aronexa_proof_reader;" >/dev/null

read -r -d '' DIGEST_SQL <<'SQL' || true
SELECT md5(
 (SELECT string_agg(id::text||tenant_id::text||display_name,'|' ORDER BY id) FROM clinic_patient)
 || '#' || (SELECT string_agg(id::text||patient_id::text||doctor_id::text||status,'|' ORDER BY id) FROM clinical_encounter)
 || '#' || (SELECT string_agg(id::text||draft_revision::text||content,'|' ORDER BY id) FROM rx_draft)
 || '#' || (SELECT string_agg(id::text||amount_paise::text||kind,'|' ORDER BY id) FROM billing_ledger)
 || '#' || (SELECT string_agg(id::text||revision::text||action,'|' ORDER BY id) FROM encounter_audit)
 || '#' || (SELECT string_agg(version||description,'|' ORDER BY version) FROM proof_schema_history)
 || '#' || (SELECT count(*)::text FROM pg_constraint WHERE contype='f' AND connamespace='public'::regnamespace)
 || '#' || (SELECT count(*)::text FROM pg_policies WHERE schemaname='public' AND policyname LIKE 'synthetic_%')
 || '#' || (SELECT count(*)::text FROM pg_class WHERE relname IN ('clinic_patient','clinical_encounter') AND relrowsecurity AND relforcerowsecurity)
);
SQL
digest() {
 docker exec -u postgres "$1" psql -X -A -t -q -U postgres -d "$DB" -c "$DIGEST_SQL"
}
SOURCE_HASH="$(digest "$SOURCE")"
TARGET_HASH="$(digest "$TARGET")"
[[ "$SOURCE_HASH" =~ ^[[:xdigit:]]{32}$ && "$SOURCE_HASH" == "$TARGET_HASH" ]] || {
 echo "FAIL: source and restored data, FK, audit or RLS mismatch"; exit 4;
}
SQL_SCOPE="BEGIN; SET LOCAL ROLE aronexa_proof_reader;
 SET LOCAL aronexa.tenant_id='aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa';
 SELECT (SELECT count(*) FROM clinic_patient)::text || ':' ||
        (SELECT count(*) FROM clinical_encounter)::text;
 ROLLBACK;"
SCOPE_RESULT="$(docker exec -u postgres "$TARGET" psql -X -A -t -q \
 -U postgres -d "$DB" -c "$SQL_SCOPE")"
grep -qx '1:1' <<< "$SCOPE_RESULT" || { echo "FAIL: restored scope policy"; exit 4; }
UNSCOPED="$(docker exec -u postgres "$TARGET" psql -X -A -t -q \
 -U postgres -d "$DB" -c \
 "BEGIN; SET LOCAL ROLE aronexa_proof_reader;
 SELECT count(*) FROM clinic_patient; ROLLBACK;")"
grep -qx '0' <<< "$UNSCOPED" || { echo "FAIL: no-scope RLS"; exit 4; }
[[ "$SOURCE_HASH" == "$(digest "$SOURCE")" ]] || {
 echo "FAIL: original synthetic source changed"; exit 4;
}
echo "Encrypted pg_dump -> independent PostgreSQL 17 pg_restore: PASS"
echo "FK/audit/RLS/synthetic contents, wrong-key/tamper, source intact: PASS"
echo "Synthetic manifest digest: $TARGET_HASH"
echo "NO ORIGINAL WINDOWS DB, REAL PATIENT RECORDS OR DEPLOYMENT TOUCHED."
