-- Testcontainers runs this as the local ephemeral PostgreSQL superuser,
-- BEFORE Flyway. Never use a test password in real environments.
CREATE ROLE aronexa_app_runtime LOGIN
    PASSWORD 'only-for-ephemeral-test-container'
    NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS;
