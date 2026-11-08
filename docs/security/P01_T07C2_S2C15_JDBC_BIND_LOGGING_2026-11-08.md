# P01-T07-C2-S2-C15 — Application JDBC bind-value logging

**Date:** 2026-11-08 IST  
**Owner direction:** [C15 Issue #59](https://github.com/ARG-Digital-Solutions/aronexa-healthcare-platform/issues/59)  
**Branch:** [Draft PR #38](https://github.com/ARG-Digital-Solutions/aronexa-healthcare-platform/pull/38)  
**Security classification:** INTERNAL TEST EVIDENCE / PRODUCTION NO-GO

## The additional exposure after C14

C14 tested server-side PostgreSQL 17 logging with bound-parameter logging limits set to `0` and found no synthetic marker. This does **not** control client-side SQL parameter logs.

[Spring Framework's StatementCreatorUtils implementation](https://github.com/spring-projects/spring-framework/blob/main/spring-jdbc/src/main/java/org/springframework/jdbc/core/StatementCreatorUtils.java) explicitly outputs parameter values when the logger is TRACE-enabled. [Hibernate/Log4j integration documentation](https://logging.apache.org/log4j/2.x/hibernate.html) likewise warns that Hibernate's `org.hibernate.orm.jdbc.bind` TRACE category can expose sensitive parameter values. Any data potentially used as a bind (patient names, tokens, identity and medication data) must be treated as sensitive.

## C15 bounded mitigation

`backend/src/main/resources/application.yml` now includes explicit levels:
```yaml
logging:
  level:
    org.springframework.jdbc.core.StatementCreatorUtils: INFO
    org.hibernate.orm.jdbc.bind: INFO
```

This documents and establishes a safer default in the bundled base application profile, including normal development startup. The Hibernate category is precautionary; the current backend uses JDBC, not a verified Hibernate persistence workflow.

The isolated PostgreSQL/Spring MockMvc acceptance test `jdbcPreparedBindLogPolicyAndControlledTraceExposure`:
1. Checks the bound application properties are INFO and logger is not TRACE-enabled.
2. Executes an actual parameterized `JdbcTemplate` SQL statement carrying a random synthetic marker while intercepting only the `StatementCreatorUtils` logger with an in-memory Logback appender. Verifies no marker logged under the normal INFO configuration.
3. Temporarily enables TRACE **only for that logger**, with `additive=false`, so nothing propagates to root/console outputs. Executes the same prepared query; asserts the synthetic marker **is** present in the memory-only captured events — a positive control proving this logger is capable of exposing JDBC bind values under an unsafe override.
4. Restores the original level, additivity and appender in `finally`; never prints the marker or complete event text, and uses no real patient data.

No SQL schema, migration or Testcontainers V10 changes are made. The test does not remotely connect to clinical systems.

## Explicit security limitations

- Spring Boot environment variables, command-line options, system properties, remote configuration, and custom Logback config **can override INFO**; a live deployment must verify the effective logging levels. Even an INFO default does not sanitize arbitrary application log messages.
- Hibernate TRACE categories, third-party JDBC/P6Spy tracing, PostgreSQL parameter settings, network gateway/proxy request logging, exception handlers, APM traces, distributed jobs and backups are **outside this test**. Require independent inspection and synthetic canary acceptance at each layer.
- The positive-control test demonstrates a **real logging confidentiality risk**, not an exploited production vulnerability. No sensitive input should be written to production diagnostic logs regardless of whether debugging is enabled.
- Do not weaken the controls by globally disabling all security/audit logs: retain safe metadata, access controls, redacted error contexts and appropriate retention.

## Acceptance and operational holds

| Gate | Evidence |
|---|---|
| C15-01 | JDBC parameter logger base config pinned to INFO — pending exact-head CI |
| C15-02 | Safe default emits no synthetic bind marker — pending exact-head CI |
| C15-03 | Positive TRACE control detects marker in *memory only* — pending exact-head CI |
| C15-04 | Existing authentication, PostgreSQL, 148+ backend tests, frontend build, SAST/SCA/npm, fresh dev unaffected — pending exact-head CI |
| C15-05 | Independent security review of actual deployed logging/APM/proxy/DB and privacy policy — NOT STARTED |

**Standing holds:** [Issue #31](https://github.com/ARG-Digital-Solutions/aronexa-healthcare-platform/issues/31) W3-E original Windows migration NO-GO/W4 BLOCKED; PR #32/#36/#38 DRAFT/UNMERGED; test-only proposed V10; no real PHI, customer pilot or C3 receptionist UI integration. Qualified independent reviewer [Issue #45](https://github.com/ARG-Digital-Solutions/aronexa-healthcare-platform/issues/45) OPEN and unassigned.

**Overall S2: OPEN / PRODUCTION NO-GO.**
