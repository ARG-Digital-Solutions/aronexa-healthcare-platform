# P01-T07-C2-S2-C2 — Independent Security Review Package

**Project:** AroNexa patient registration, staff/receptionist workflow  
**Date:** 2026-11-07 IST  
**Classification:** REVIEW-READY DOCUMENTATION ONLY / NOT SECURITY-APPROVED / NOT CUSTOMER-READY  
**Tracking:** [Issue #44](https://github.com/ARG-Digital-Solutions/aronexa-healthcare-platform/issues/44)  
**Review candidate:** [Draft PR #38](https://github.com/ARG-Digital-Solutions/aronexa-healthcare-platform/pull/38)  
**Design proposal:** [S2-C2 Production Security ADR](P01_T07C2_S2C2_PRODUCTION_SECURITY_ADR_2026-11-07.md)

## 1. Executive reviewer instruction

Assess the **existing synthetic registration implementation** and challenge the **proposed** production design. Do not mistake successful CI for an independent penetration test or a cleared release. Reviewer may inspect and reproduce test-only code in a **fresh disposable PostgreSQL 17 Testcontainers environment**, with synthetic records only. No original Windows database, clinic data, production keys, private patient reports, Flyway W4 Apply or live endpoint access is authorized.

**Scope of independent review:** PostgreSQL privilege and RLS boundary, session/proof/nonce authorization, tenant and branch segregation, concurrency/replay, logging of sensitive SQL parameters, access control, operational key custody, rate limiting, audit/restore requirements, and differences between offline Windows and cloud deployment.

**Excluded from current review execution:** actual customer data, production exposure, penetration testing against non-owned systems, modifying GitHub branch protection or secrets, customer endpoint trials, migrations on a persistent instance, merging PRs.

## 2. Reproducible evidence manifest — as of 2026-11-07

| Item | Exact source/evidence | Meaning |
|---|---|---|
| Last accepted C1 code+docs HEAD | \`8ee15b53b63879a16cd57f3a0d669dbdc8ae4f3a\` | Pre-review design baseline; do not substitute latest HEAD without checking diff |
| PR Quality Gate | [Run #42385701535](https://github.com/ARG-Digital-Solutions/aronexa-healthcare-platform/actions/runs/42385701535) | PASS backend, frontend, docs, secret/scope checks at baseline |
| Fresh Dev Acceptance | [Run #42385701619](https://github.com/ARG-Digital-Solutions/aronexa-healthcare-platform/actions/runs/42385701619) | PASS normal dev startup at baseline (registration API remains gated) |
| Backend test detail | [Run #42385640404](https://github.com/ARG-Digital-Solutions/aronexa-healthcare-platform/actions/runs/42385640404) | 124 tests / 0 failures / 0 errors / 0 skipped; includes 18 isolated synthetic integration tests |
| Consent / C1 UI demo | [Draft PR #36](https://github.com/ARG-Digital-Solutions/aronexa-healthcare-platform/pull/36) | Independent synthetic UI; not connected to protected API |
| Earlier migration HOLD | [Issue #31](https://github.com/ARG-Digital-Solutions/aronexa-healthcare-platform/issues/31) and [Draft PR #32](https://github.com/ARG-Digital-Solutions/aronexa-healthcare-platform/pull/32) | W3-E NO-GO / original W4 Apply BLOCKED |
| S2-C1 security acceptance | [Issue #43](https://github.com/ARG-Digital-Solutions/aronexa-healthcare-platform/issues/43) | Lock-order test outcomes and outstanding limitations |
| Proposed test-only schema | \`backend/src/test/resources/db/proposed/V10__c2_synthetic_staff_registration.sql\` | Not on production Flyway path and must stay there |
| Auth implementation | \`backend/src/main/java/com/argdigital/aronexa/auth/AuthRepository.java\` | Bearer-session lookup and session revocation |
| Auth API | \`backend/src/main/java/com/argdigital/aronexa/auth/AuthController.java\` | \`/auth/me\` uses DTO excluding internal session ID |
| SQL caller | \`backend/src/main/java/com/argdigital/aronexa/patient/SyntheticRegistrationService.java\` | Short-lived proof, bounded MRN retry and transaction scope |
| Proof cryptography | \`backend/src/main/java/com/argdigital/aronexa/patient/SyntheticRegistrationProof.java\` | V3 canonicalization and HMAC with signed key ID/session |
| HTTP and throttle | \`SyntheticRegistrationController.java\`, \`SyntheticRegistrationThrottleConfiguration.java\` under patient package | Synthetic-only POST; single-JVM limiter |
| Test harness | \`SyntheticRegistrationIntegrationTests.java\`, \`SyntheticRegistrationHttpAcceptanceTests.java\`, \`SyntheticRegistrationAttemptLimiterTests.java\` under backend tests | Synthetic SQL adversarial, HTTP and quota checks |

**Evidence rule:** This packet is itself a *later documentation commit*. The source SHA/run IDs above prove the **previous application-code baseline**, not an independently approved production design. Attach the latest documentation CI run separately and verify that only documentation changed.

## 3. Safe reproduction guidance

Preferred first step: inspect GitHub PR #38 and its successful Actions run; no local state change required. If an authorized reviewer has Docker/Testcontainers access, use a **fresh checkout** of the feature branch (not an existing AroNexa Windows working tree), with no persistent Docker volume and **no** original PostgreSQL credentials or environment variables loaded.

Suggested read-only or disposable commands from a clean checkout:

\`\`\`powershell
git status --short
git rev-parse HEAD
git diff --name-only main...HEAD
mvn -f backend/pom.xml -B verify
\`\`\`

The Maven verify command can create ephemeral Testcontainers databases, so run it **only inside the review machine's isolated Docker environment**. Do not run \`flyway migrate\`, \`flyway repair\`, Docker Compose pointed to original Windows services, or any destructive SQL against persistent data. Before reproducing, review the test profile and database property resolution to ensure no test can use a persistent database. Record the exact Docker/PostgreSQL/Java versions and CI run URL.

## 4. Adversarial security verification matrix

| ID | Test/attacker scenario | Baseline evidence | Independent disposition required |
|---|---|---|---|
| REV-01 | Stolen \`aronexa_patient_runtime\` password + forged tenant/membership GUC without valid proof | PASS, synthetic Testcontainers | Review grants and cross-tenant proof verification |
| REV-02 | Tampered name, branch, MRN, expiry, key ID or missing/expired/replayed nonce | PASS, synthetic | Re-run and inspect canonical serialization and time source |
| REV-03 | Disabled/future/retired verifier key rejects otherwise valid signed request | PASS, synthetic | Confirm key custody and real rotation/rollback design |
| REV-04 | SQL LOGIN attempts to SELECT proof key or nonce, or write identity tables | PASS in current tests | Review \`pg_roles\`, \`has_table_privilege\`, function \`EXECUTE\`, schema CREATE and RLS under all roles |
| REV-05 | Tenant, active staff, role permission, branch and module disabled | PASS, current synthetic negative tests | Test revocation *during* transaction; currently PENDING |
| REV-06 | Session revoked after bearer authentication or expired before SQL gate | PASS, synthetic | Define expiry policy at transaction commit |
| REV-07 | Registration-first/logout-second and logout-first/registration-second | PASS, bounded 2-connection lock tests | Multi-thread stress, deadlocks, cancellation, long transactions PENDING |
| REV-08 | Duplicate same-name/DOB cannot auto-link; concurrent key retries safe | PASS, synthetic | Review real identity resolution and false positive/negative risk |
| REV-09 | Forced MRN uniqueness collision -> rollback, fresh transaction and maximum 3 attempts | PASS, synthetic | Alerting, recovery, unique constraint restore tests PENDING |
| REV-10 | Authenticated staff exceeding 12/min returns no-store 429; tenant quota and cache cap | PASS, one JVM | Cross-replica/Redis outage and pre-auth throttling NOT IMPLEMENTED |
| REV-11 | App signing key and patient SQL password both stolen | NOT TESTED / HIGH RISK | Independent threat model and containment design |
| REV-12 | PostgreSQL function owner, migrator or superuser compromised | NOT TESTED / HIGH RISK | Privilege isolation, blast radius, backups/rotation |
| REV-13 | Patient name, DOB or proof captured in SQL logs, APM, proxy or crash dump | NOT VERIFIED / CRITICAL | Protected synthetic canary trace and redaction evidence required |
| REV-14 | Multiple app instances concurrently enforce global quota and revoke sessions | NOT IMPLEMENTED | Distributed architecture decision + load/chaos test |
| REV-15 | Backup/restore replays or loses nonce, MRN, audit, role scopes or keys | NOT VERIFIED | Fresh restore rehearsal and checksum/record invariants |
| REV-16 | API responds safely to 401/403/409/429/503, malformed data, oversized payload and interrupted client | Partial PASS for tested statuses; oversized/timeout PENDING | Full boundary-level acceptance |
| REV-17 | Real-world patient privacy/consent/medical-record handling and branch privilege | NOT IMPLEMENTED or REVIEWED for C2 | Privacy and clinical reviewer signoff before PHI |
| REV-18 | Test-only profile and proposed V10 cannot activate under normal production settings | Normal dev tested; production deployment NOT VERIFIED | Deployment configuration and image inspection |

**These statuses refer only to the synthetic tests/observations indicated. They are not claims of independently verified compliance.**

## 5. Required independent-review outputs and stop criteria

Reviewer must produce a dated report identifying the **exact source commit, database privileges/roles, test environment, attack setup, steps, actual evidence, severity, reproducibility, accountable owner and residual risks**. For each REV case, record PASS / FAIL / NOT RUN / NOT APPLICABLE with justification and links to redacted evidence. Unperformed tests remain NOT RUN; they may not be marked PASS by inference.

Minimum attachments before any technical security signoff:
- Threat model with attacker capabilities, assumptions and compensating controls.
- Signed-off SQL privilege/RLS/SECURITY DEFINER inspection with scripts/logs (no patient data).
- Auth/session/role/branch/module revocation race tests and failure-injection results.
- Key custody, dual-key rotation, key compromise and recovery tests.
- Log/trace canary redaction results across JVM, JDBC, PostgreSQL, proxy, APM and backups.
- Distributed admission and authentication abuse tests for deployment topology.
- Restore rehearsal with audit/nonce/idempotency consistency and environment isolation.
- Dependency/SAST/DAST report and disposition of all high/critical findings.
- Privacy/clinical acceptance and separate owner go/no-go for planned product environment.

**Automatic NO-GO if:** any unresolved exploitable cross-tenant access, privilege bypass, unauthorized writes, unredacted proof or patient identity in telemetry, unexpected production API activation, unapproved persistent migration, absent independent reviewer signoff, or failed recovery test. Security signoff alone does not authorize merging or customer deployment.

## 6. Independent reviewer worksheet (complete externally)

- Reviewer name/organization: **UNASSIGNED**
- Reviewer independence and conflict declaration: **PENDING**
- Scope and pinned commit: **PENDING**
- Test environment and prohibited original DB checked: **PENDING**
- Architecture choices D01–D07 accepted/changes requested: **PENDING**
- REV-01 through REV-18 results and evidence links: **PENDING**
- High/critical finding IDs, severity, owners, remediation PRs: **PENDING**
- Post-remediation retest and approval: **PENDING**
- Production signing-key custody / rotation owner: **UNASSIGNED**
- Database administrator / DevSecOps / privacy / clinical approvers: **UNASSIGNED**
- Independent security disposition: **NOT REVIEWED**
- AroNexa owner GO/NO-GO for C3, merge and migration (separate decisions): **NOT AUTHORIZED**

## 7. Governance and change control

Review-readiness **does not** close S2 or permit C3. Issue #31 W3-E NO-GO/W4 original Windows Apply BLOCKED. PR #32 (migration), #36 (receptionist demo), #38 (C2 backend) remain DRAFT/UNMERGED. Proposed V10 remains test-resource only; real PHI/customer use and all deployment remain BLOCKED.

**Outcome of this package: an independently executable review plan, NOT a completed independent review.**