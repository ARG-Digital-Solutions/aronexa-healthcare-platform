# Clinic OPD registration client-to-database acceptance

Requirement: persistent registration integration step in PR #102.
Classification: INTERNAL_FOUNDATION / NOT_READY.

## Change

The existing registration acceptance class now starts a real Spring server on a
random loopback-only port. Its new test launches the actual TypeScript registration
client with Node 24 against that server and the same disposable PostgreSQL 17
fixture. Existing MockMvc acceptance cases remain in the class.

The test deliberately discards the first response after HTTP 201 has arrived,
then retries the same client submission. It requires a 200 replay receipt and
independently checks PostgreSQL for exactly one patient, registration, idempotency
record and write-audit row. Stored name, EXACT precision and date of birth must
match the submitted values. A duplicate click shares the initial request. A fresh
duplicate request requires review, a foreign tenant's branch is denied, and a
revoked session is denied. Denied tenants must have no patient, registration,
audit, nonce or idempotency side effects.

The Node runner receives ephemeral fixture values through stdin. It restricts its
target to numeric IPv4 loopback and the registration path, emits only a fixed
completion marker or bounded stage name, and never prints receipt/credential
data. The Java launcher has a 75-second timeout and destroys a remaining child.

## Execution and evidence

- `node --check frontend/test/integration/registration-http.mjs` passed locally.
- `npm --prefix frontend test` passed all 131 frontend tests, no failures/skips.
- Maven/Docker are unavailable in the editing environment. The real Spring/SQL
  acceptance is executed by the existing Application CI job, which already sets
  up Node 24 before Maven. New-commit CI evidence is recorded in the PR body.
- Focused invocation from repository root requires Node 24, Java 21, Maven and
  Docker: `mvn -B -f backend/pom.xml -Dtest=SyntheticRegistrationHttpAcceptanceTests test`.
- The nested Node runner is launched by Maven only, not by the frontend unit-test
  glob. Missing Node or runner fails acceptance rather than silently skipping it.

## Limits and next work

This tests the real HTTP/filter/service/SQL path with disposable staff sessions
and synthetic data. It does not test browser origin enforcement, the reception
screen, interactive login, a full application restart or production installation.
Database writes occur only in Testcontainers. The original Windows database is
untouched. No production profile, role grant, migration location, module activation,
workflow or dependency has changed.

Inspection confirms that V10 expects a restricted NOLOGIN registration writer and
a separately installed proof-verification key. The normal dev bootstrap currently
creates only app/auth/patient login roles. Runtime migration and secure key
provisioning remain implementation work; removing the test-profile guard alone
would not deliver a working or appropriately provisioned clinic installation.
