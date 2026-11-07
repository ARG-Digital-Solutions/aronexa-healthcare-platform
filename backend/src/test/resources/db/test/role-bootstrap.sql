-- Testcontainers runs this as the local ephemeral PostgreSQL superuser,
-- BEFORE Flyway. Never use a test password in real environments.
CREATE ROLE aronexa_app_runtime LOGIN
    PASSWORD 'only-for-ephemeral-test-container'
    NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS;

-- Dedicated read-only patient-registration runtime (synthetic containers ONLY).
CREATE ROLE aronexa_patient_runtime LOGIN
    PASSWORD 'only-for-ephemeral-patient-role'
    NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS;

-- Separate restricted runtime identity for authentication SQL.
CREATE ROLE aronexa_auth_runtime LOGIN
    PASSWORD 'only-for-ephemeral-auth-role'
    NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS;

-- C2 disposable-only restricted function execution owner. NOLOGIN means it
-- cannot be used as a JDBC connection and has no bypass-RLS capability.
CREATE ROLE aronexa_registration_writer NOLOGIN
    NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS;
