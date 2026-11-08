# P01-T07-C2-S2-C14 — PostgreSQL bind-value logging risk and verification

**Date:** 2026-11-08 IST  
**Authorized scope:** [C14 Issue #58](https://github.com/ARG-Digital-Solutions/aronexa-healthcare-platform/issues/58), ephemeral PostgreSQL 17 Testcontainers only  
**Review branch:** [DRAFT PR #38](https://github.com/ARG-Digital-Solutions/aronexa-healthcare-platform/pull/38)  
**Safety classification:** INTERNAL SYNTHETIC SECURITY EVIDENCE — NOT PRODUCTION CONFIGURATION.

## Why C14 is needed

C13's JVM console canary check did **not** observe PostgreSQL server logs, JDBC bind tracing, APM, ingress/proxy logs, exception collection or backups. Those systems may receive clinical identities, bearer tokens or signed proof parameters. Even when an application uses parameterized SQL, PostgreSQL's log facilities may include the bound values.

PostgreSQL 17 official documentation: https://www.postgresql.org/docs/17/runtime-config-logging.html

- `log_statement=none` is PostgreSQL's default; changing it to `all` enables query logging, which can include bind parameters.
- `log_parameter_max_length=-1` is the **default**, permitting full bind-parameter values in ordinary statement logs. Setting it to `0` disables such parameter logging.
- `log_parameter_max_length_on_error=0` is PostgreSQL's **default**, excluding bind-parameter detail in errors. Keep this at `0` if the organization does not explicitly approve sensitive parameter logging.
- `log_min_error_statement=ERROR` logs the statement text associated with SQL errors. A bind-value limit does **not** redact values directly concatenated into SQL literals, database-generated error text, notices, explicit application logs or third-party telemetry.
- `log_min_duration_statement` and monitoring extensions need separate review; they can log query text/parameter information depending on configuration.

## Isolated C14 experiment

`backend/src/test/java/com/argdigital/aronexa/patient/SyntheticPostgresLogRedactionAcceptanceTests.java` uses a disposable PostgreSQL 17 container with a deliberately observable configuration:

```text
log_statement=all
log_parameter_max_length=0
log_parameter_max_length_on_error=0
log_min_error_statement=error
```

The test checks the **actual live settings**, executes a successful parameterized read and a parameterized SQL-error probe with a fresh, synthetic-only random marker, requires observable structural SQL statement/error log entries, and fails if the bound marker appears in the PostgreSQL container logs. It deliberately never prints the marker or raw logs in CI. The test does not make any change to normal development, production or original Windows PostgreSQL configuration.

**Redaction interpretation:** If this test passes, it verifies one safe configuration for **bound-parameter logging in disposable PostgreSQL**, not proof of clinical secrecy in a deployed AroNexa installation. It is not a whole-SQL redactor.

## Production prerequisite: distinct controls to verify

| Layer | Required review evidence | Current C14 scope |
|---|---|---|
| PostgreSQL server | `SHOW` settings for every deployment, error and slow query sampling, exceptions, privileged extension audit, log retention | Test-only DB config and synthetic canary |
| JDBC/Hikari, Spring and ORM | SQL bind trace disabled, SQL exception safe handling, PII masking, no token/credentials in log messages | Not inspected by C14 |
| Server and reverse proxy | Request/body/Authorization header suppression, trace/response capture, ingress limits and logging format | Not inspected by C14 |
| APM, observability and cloud services | Redaction before exporting traces/events, storage residency, retention, access, deletion | Not inspected by C14 |
| Database backup and incident support | Export confidentiality, encrypted archive/storage, restricted restore privileges, diagnostic bundle policies | Not inspected by C14 |
| Multi-tenant clinical audit | Separate lawful, tamper-evident audit identity fields from unsafe diagnostic payloads | Not inspected by C14 |

Security engineers should choose diagnostic verbosity appropriate to incidents **without exposing names, dates, identity documents, signed proof or credentials**. Grant log access on least privilege; preserve appropriate security audit metadata while protecting medical information. Do not apply blanket `log_min_error_statement=PANIC` as a production workaround without reviewing incident-response consequences.

## Standing holds

Independent review [Issue #45](https://github.com/ARG-Digital-Solutions/aronexa-healthcare-platform/issues/45) remains OPEN and unassigned. Original Windows database W3-E NO-GO/W4 Apply BLOCKED [Issue #31](https://github.com/ARG-Digital-Solutions/aronexa-healthcare-platform/issues/31); PRs #32/#36/#38 DRAFT and UNMERGED; proposed V10 exists ONLY in test resources. No real PHI, original DB changes, customer pilot, receptionist UI integration, production migration or release.

**Overall S2 remains OPEN / PRODUCTION NO-GO, regardless of C14 automated result.**
