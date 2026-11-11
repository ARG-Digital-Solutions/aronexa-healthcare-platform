# Clinic OPD registration transport increment

Requirement: Clinic OPD V1 integration milestone, persistent registration step.
Classification: INTERNAL_FOUNDATION / NOT_READY.

## Design and execution

Reuse the existing Spring registration contract through a same-origin TypeScript
HTTP client. One immutable submission holds its request body, branch, bearer token
and cryptographically random idempotency key in memory. Explicit retries reuse
that submission. Concurrent calls share the in-flight write; confirmed receipts
are reused without another write. No automatic retry or local success fallback.

This is a bounded transport increment on PR #102. The existing screen remains a
demo. Registration is still enabled only in the disposable backend profile.
Production migration, protected proof-key provisioning, branch-scoped patient
search, login/session UI and the reception event-handler wiring remain pending.
No dependency, CI workflow, runtime profile, database grant or migration changes.

Files:
- `frontend/src/registrationClient.ts` implements `createRegistrationAttempt`
  and safe typed error codes.
- `frontend/test/registration-client.test.mjs` covers its request/response and
  retry behavior using controlled transport responses.

Execution completed:
- Wrote the contract tests first; the initial run failed because the client
  module did not yet exist.
- Implemented the client and ran the focused suite: 27 passed, zero failures/skips.
- Ran `npm run build`: TypeScript, all four Vite builds and all 131 frontend
  tests passed, zero failures/skips. npm emitted an existing environment
  `http-proxy` configuration warning.
- Reviewed request mapping against `SyntheticRegistrationController`, receipt
  shape against `SyntheticRegistrationService`, bearer format against
  `AuthService`, and MRN format against `PatientRegistrationRules`.

## Acceptance and consumer obligations

- FULL DOB maps to EXACT; UNKNOWN maps to a null date. Unsupported phone storage
  is rejected visibly instead of silently dropping the entered number.
- A validated server 201 receipt means created; a validated 200 receipt must
  have `replayed=true`. Malformed responses and transport/server failures mean
  outcome unknown, never saved.
- 401, 403, 400, 409 and 429 produce bounded error codes. Backend text and raw
  transport errors are not exposed. Duplicate review does not select a patient.
- Requests reject redirects and use same-origin mode, omitted cookies, no-store
  caching and a 15-second timeout. These options are unit-tested; browser
  enforcement and full Spring/PostgreSQL execution are not claimed here.
- Preserve the attempt while its outcome is unknown. Do not offer a fresh
  submission merely because a response was lost. Destroy session-bound state
  on logout; reauthentication/reload recovery needs a durable reconciliation
  design before the live screen is enabled. Tokens and intake must never be
  placed into localStorage, URLs or logs.
- Do not use demo duplicate matching as authorization or live search. Server
  tenancy, permissions, module entitlement and session checks remain mandatory.

## Evidence limits and next implementation

These new tests are frontend transport tests with controlled responses, not
end-to-end patient persistence evidence. The prior baseline's 203 backend tests
passed at `233418c86eec3f81c1f2de20219c412490a6162f`; new-head CI must be checked
separately. Central QA and live reception/API/browser acceptance remain pending.

Next, complete runtime migration/key provisioning and an authenticated read path,
then wire reception and verify registration, retry and restart persistence against
disposable PostgreSQL. Appointments and consultation follow that accepted path.
