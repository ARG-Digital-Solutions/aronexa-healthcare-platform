# Phase 00 — security acceptance criteria and evidence boundaries
Status: DESIGN BASELINE; NOT runtime certification.

## Threat model coverage reviewed
Tenant IDOR and pooled context leakage; cross-branch/doctor access; admin support escalation; token/session compromise; uploaded content and provider webhooks; unauthorized or modified prescription; wrong recipient and leaked document; payment duplication; local licence lockout; backup theft and restoration; gamete/embryo identification; AI prompt injection and PHI exfiltration.

## Mandatory Phase 01 test contracts
- Tenant A cannot read/update/list Tenant B records through API, repositories or analytics including forged IDs and missing context.
- PostgreSQL application role cannot BYPASSRLS; deny on no/invalid tenant session setting; interleave pooled connections and transactions under concurrent tenant requests.
- Parent-child FK constraints disallow inconsistent tenant ownership. Branch/doctor/resource scoping cannot be bypassed using roles, direct objects or ID guessing.
- Super Admin is not granted clinical reading; emergency/support path remains disabled until approved, logged and independently gated.
- Session expiry/revocation, password handling, rate limiting and audit are tested on real APIs.
- Secrets never committed; test data synthetic; database migration credentials separate and not used by application runtime.

## Future clinical and deployment controls
Clinical finalization/amendment testing in Phase 02; messaging privacy/replay/provider failure in Phase 04; clinical UAT, backup restore and release checks in Phase 05; licensing/continuity and offline restore in Phase 07; specimen traceability in Phase 11; AI draft provenance/wrong-patient/prompt-injection tests before AI customer use.

## Evidence status
None of these clinical/API/PostgreSQL tests have run because application scaffolding is absent. Design review is not implementation certification. Assign explicit security and medical sign-off at each relevant gate. No public launch before required legal applicability review.
