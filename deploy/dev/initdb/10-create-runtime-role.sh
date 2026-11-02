#!/bin/sh
# Only executed automatically during initial creation of the DEV database volume.
# Not a production bootstrap. Never log the password.
set -eu
: "${ARONEXA_RUNTIME_DB_PASSWORD:?Set a strong runtime role password}"
: "${ARONEXA_AUTH_DB_PASSWORD:?Set a different auth-only role password}"
psql -X -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" \
    -v runtime_password="$ARONEXA_RUNTIME_DB_PASSWORD" <<'SQL'
SELECT format(
    'CREATE ROLE %I LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS PASSWORD %L',
    'aronexa_app_runtime', :'runtime_password'
)
WHERE NOT EXISTS (SELECT 1 FROM pg_catalog.pg_roles WHERE rolname = 'aronexa_app_runtime')
\gexec
SQL
psql -X -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" \
    -v auth_password="$ARONEXA_AUTH_DB_PASSWORD" <<'SQL'
SELECT format(
    'CREATE ROLE %I LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS PASSWORD %L',
    'aronexa_auth_runtime', :'auth_password'
)
WHERE NOT EXISTS (SELECT 1 FROM pg_catalog.pg_roles WHERE rolname = 'aronexa_auth_runtime')
\gexec
SQL
