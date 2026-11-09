# P01-T07-INT-REG-01 — Contract and gaps

**Status:** IMPLEMENTED FOR REVIEW / UNVERIFIED EXECUTION — NOT_READY  
**Owner scope:** Approved test-only changes, 2026-11-09. No frontend API activation, production change, Flyway deployment or merge.

## Immutable baseline
- Backend parent: PR #38 `cfdb03c631c2ed6df687b71d68fcc890a1eb9cc4`.
- Read-only frontend contract: PR #61 `52809efbc077cd6abb6860d870cc487cc8eddc42`.
- Writable branch: `codex/p01-t07-int-reg01-contract-20261109`.
- No branch cherry-picks, rebase or merges.

## Protocol v1 and field mapping
See `backend/src/test/resources/contracts/p01-t07-int-reg01-registration-v1.json` for the authoritative **proposed test contract**, not a production API promise.

| Frozen Reception UI | Protected C2 test-only API | Disposition |
|---|---|---|
| `name` | `displayName` | Trim/collapse whitespace; UI 2–100 characters; backend 2–180 |
| `precision=UNKNOWN`, `dob=""` | `birthDatePrecision=UNKNOWN`, `dateOfBirth=null` | Supported test fixture |
| `precision=FULL`, ISO date | `birthDatePrecision=EXACT`, ISO date | Supported test fixture; calendar/future validation |
| Optional `phone` | No field or secure persistence | **BLOCK** when nonempty; not suitable for live integration |
| `DEMO-xxxx` | Server-generated `patientId`, `registrationId`, `mrn` | Not interchangeable; no UI ID remap authorized |
| Branch display name | Authorized branch UUID in URL | Server-authorized tenant/branch context required |
| Duplicate candidates in browser state | `DUPLICATE_REVIEW_REQUIRED` 409 | No secure candidate retrieval; no automatic merge |
| `ESTIMATED` | Backend supports `ESTIMATED` | Frontend has no corresponding state; unsupported UI mapping |

Only `POST /api/v1/branches/{branchId}/patient-registrations` under `test & c2-synthetic & !dev & !prod` is in scope. Bearer token and high-entropy 20–128-character `Idempotency-Key` are mandatory. New registration returns 201 with `patientId,registrationId,mrn,replayed=false`; identical same-key retry returns 200 with `replayed=true`. Changed payload with same key: 409 `IDEMPOTENCY_CONFLICT`. Suspected duplicate with new key: 409 `DUPLICATE_REVIEW_REQUIRED`, without identity merge. 400 invalid input; 401 unauthenticated; 403 forbidden; 413 size limit; 429 throttle; 503 unavailable. Security filters may not return the controller error-body shape for 401/403.

## Acceptance evidence requirements
The added test class exercises fixture mapping, 201/200 replay and stable identity, changed-key conflict, no auto-merge, exact DOB, invalid date, missing bearer, denied role, denied branch, and phone-block policy against disposable PostgreSQL. Existing `SyntheticRegistrationHttpAcceptanceTests` and `SyntheticRegistrationIntegrationTests` already exercise session revocation, tenant isolation, throttle, concurrency, SQL privileges, RLS and signing-proof resistance. **Those existing tests must be rerun on this exact new commit, not assumed passing from old PR #38 evidence.**

Run from a secure isolated clone (no original Windows database):
```bash
git fetch origin
git switch codex/p01-t07-int-reg01-contract-20261109
git diff --name-only cfdb03c631c2ed6df687b71d68fcc890a1eb9cc4 HEAD
mvn -B -f backend/pom.xml verify
cd frontend && npm ci --no-audit --no-fund && npm run build
```
Run existing repository static, secret, scope, docs and dependency checks as applicable. Preserve immutable reports, exit codes, SHA, timestamps and CI URLs in the build log. Confirm only the four approved paths are changed.

## Gaps and stop conditions
1. **G-01 / BLOCKER:** Backend contact storage and patient consent are unsupported. A nonempty UI phone number must not be silently discarded or sent.
2. **G-02 / BLOCKER:** Secure duplicate candidate retrieval and matching authorization do not exist for frontend integration. Backend 409 is insufficient for the UI's existing review dialog.
3. **G-03 / BLOCKER:** Frozen UI uses in-memory `DEMO-xxxx` while C2 returns opaque generated server identity and MRN. No UI contract consumer exists.
4. **G-04 / BLOCKER:** Frontend lacks authenticated tenant/branch/session binding and secure idempotency-key lifecycle. Do not infer tenant/branch from labels or trust user headers.
5. **G-05 / BLOCKER:** Test-resource proposed V10 is not an authorized production migration; do not touch original Windows DB or deploy.
6. **G-06 / GATE:** Independent code/security review and fresh actual CI + Testcontainers results remain required.
7. **G-07 / GATE:** Profiles `dev` and `prod` must never expose synthetic endpoint; confirm via existing profile-gate checks and runtime acceptance.
8. **G-08 / GATE:** Unauthorized extra fields may be ignored by Jackson. Never interpret this as permission to accept or persist phone information.
9. **G-09 / GATE:** UI name length max 100 differs from backend max 180. Versioned contract must preserve tighter UI restriction.
10. **G-10 / GATE:** No test in this increment claims customer workflow completion or actual frontend-to-backend API connectivity.

**Review disposition:** test-only contract preparation, not production readiness. Any required backend main-source, frontend, migration, security-config or deployment change must have a separate owner authorization.
