# Default-off registration HTTP boundary implementation plan

> **For agentic workers:** Use test-first implementation and a fresh AI code review. AI review is not independent human acceptance.

**Goal:** Exercise a main-source registration HTTP boundary against the existing restricted writer on fresh fictional PostgreSQL, without activating a clinical endpoint.

**Architecture:** Follow the existing default-off OPD controller pattern. An explicit flag permits the HTTP boundary, but no main-source writer factory, database migration or key loader is supplied. A test-only configuration supplies the existing executor and provisions only its own disposable PostgreSQL 17 container.

**Tech stack:** Existing Java 21, Spring Boot, PostgreSQL 17 and Maven; no dependencies added.

**Spec:** `docs/phases/OPD_V1_PERSISTENT_JOURNEY_CONTRACT.md`, `docs/WEB_FIRST_DELIVERY.md`, the owner's web-first instruction and standing bounded implementation authorization.

## Constraints and decisions

- Preserve AGENTS.md rule 27; exact-source Central QA is pending, never inferred from local tests. WIN-11B remains NOT_READY.
- Do not activate workflows, main migrations, clinical modules or the new flag; do not access original databases, merge or deploy.
- Keep V10, provisioning and preflight inert. The preflight's installer credentials must never be given to a runtime pool. Checksum approval, key custody, rotation, encrypted recovery and installer rehearsal remain unresolved.
- Exclude existing synthetic controller profiles from the new handler. Preserve all existing test methods and the existing WIN11P execution allowlist.
- Generalize existing bounded admission helpers without changing the synthetic test contract. These counters are per JVM; multi-instance production admission remains unapproved.
- The optional writer is checked after authorization and module checks. Missing writer returns a generic 503; nothing auto-provisions it.

## Review focus

- A flag alone must never create a usable writer; flag absent and conflicting synthetic profiles must deny or retain only their original handlers.
- Malformed authenticated requests consume quotas; changing branch or idempotency headers cannot evade them.
- Enforce the actual streamed 4096-byte body limit, including requests without Content-Length.
- Check live database identity, non-owner RLS, revocation, tenant/branch/module/permission, durable replay and safe error bodies.
- Shut down new Spring test contexts before their disposable database; never hide Surefire/Hikari diagnostics.

## Task 1: HTTP boundary and admission, one deliverable

- [ ] Add and run a failing real HTTP/PG17 test for the new main handler, using no old candidate controller.
- [ ] Add failing boundary tests for disabled configuration and enabled configuration without a writer.
- [ ] Add `RegistrationHttpController`; permit its authenticated route only with `aronexa.patient.registration.reviewed-activation=true`, default false.
- [ ] Reuse the existing executor via optional injection; enforce permission, session and module before returning receipt or missing-writer 503. Keep safe 400/403/409/503 responses and no-store.
- [ ] Extract shared body limiting and bounded quotas; retain existing synthetic wrappers and use the same helpers in the new explicitly gated admission configuration.
- [ ] Verify real durable receipt/replay, negative authorization, denied direct SQL/key reads, malformed requests, streamed body caps and quotas.
- [ ] Run existing candidate and authorization regression suites, architecture checks and the ordinary suite without guarded tags. Record every diagnostic and count.
- [ ] Obtain fresh AI review, fix material findings with regression evidence, commit and reproduce on the exact commit.
- [ ] Update draft review evidence and QA issue #38. Any corresponding QA registration is a proposal only; no active allowlist or governance change.

## Acceptance and handoff

Local synthetic evidence can establish this boundary's behavior. It cannot establish production migration approval, protected key storage, encrypted recovery, independent operator acceptance or Central PASS. Review artifacts must name the exact source SHA and preserve all eight guarded methods as NOT_RUN.
