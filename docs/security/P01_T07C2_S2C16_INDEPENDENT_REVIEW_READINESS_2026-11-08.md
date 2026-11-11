# P01-T07-C2-S2-C16 — Independent Security Review Readiness and Release Gate

**Dated:** 2026-11-08 IST  
**Project:** AroNexa Healthcare Platform — isolated staff/receptionist registration foundation  
**Status:** REVIEW-HANDOFF READY, INDEPENDENT ASSESSMENT NOT STARTED, PRODUCTION NO-GO  
**Review control:** [Issue #45](https://github.com/ARG-Digital-Solutions/aronexa-healthcare-platform/issues/45), [draft PR #38](https://github.com/ARG-Digital-Solutions/aronexa-healthcare-platform/pull/38)  
**Original 18-case specification:** [C2 reviewer packet](P01_T07C2_S2C2_INDEPENDENT_SECURITY_REVIEW_PACKET_2026-11-07.md) (historical C1 and C8 evidence is preserved; **this document takes precedence for CURRENT status**)

## 1. Exact implementation candidate and provenance

- **Pinned IMPLEMENTATION/SOURCE candidate before this documentation update:** `43f784fd06bcdf88b34ba29d1872c36a364c99cf` (C15). If documentation updates advance branch HEAD, verify the GitHub compare before claiming the tests ran on a new code commit.
- **Branch:** `feature/p01-t07c2-isolated-registration-core`. PR #38 checked OPEN, **DRAFT**, **NOT MERGED**; no submitted/review-requested external reviewer verified in the latest check. PR #32 and PR #36 also OPEN/DRAFT/UNMERGED.
- **Exact implementation CI:** [PR Quality Gates #42456549265](https://github.com/ARG-Digital-Solutions/aronexa-healthcare-platform/actions/runs/42456549265) **PASS — 149 Maven tests, 0 failures, 0 errors, 0 skipped** (including 18 synthetic HTTP integration and 32 synthetic registration SQL integration tests); frontend reproducible build, Java/TS SAST, Maven/npm SCA, npm audit, scope/docs/tracked-secret checks PASS.
- **Fresh normal development runtime:** [#42456549264](https://github.com/ARG-Digital-Solutions/aronexa-healthcare-platform/actions/runs/42456549264) **PASS**, with test-profile-only registration not enabled in ordinary dev.
- **Database state:** Original Windows PostgreSQL V8→V9 workflow [Issue #31](https://github.com/ARG-Digital-Solutions/aronexa-healthcare-platform/issues/31) W3-E **NO-GO / W4 APPLY BLOCKED**. Proposed V10 synthetic registration remains **test-resource-only**, not part of production Flyway.
- **Independence status:** [Issue #45](https://github.com/ARG-Digital-Solutions/aronexa-healthcare-platform/issues/45) OPEN, unassigned. Reviewer identity, independence declaration, approved access, independent testing and signed opinion are **NOT AVAILABLE**. Internal CI must never be substituted for these.

## 2. Engineering evidence inventory — not reviewer sign-off

| Increment | Evidence | Engineering disposition | Remaining boundary |
|---|---|---|---|
| C1–C3 | [Review packet](P01_T07C2_S2C2_INDEPENDENT_SECURITY_REVIEW_PACKET_2026-11-07.md), [C3 findings #46](https://github.com/ARG-Digital-Solutions/aronexa-healthcare-platform/issues/46) | Initial authorization design, observed defects and test foundation | Design choices, threat model and independent assessment |
| C4–C5 | [C4 #47](https://github.com/ARG-Digital-Solutions/aronexa-healthcare-platform/issues/47), [C5 #48](https://github.com/ARG-Digital-Solutions/aronexa-healthcare-platform/issues/48) | Revocation races reproduced and tenant lock proved in synthetic DB | Privileged administration bypass and production transaction policy |
| C6–C7 | [C6 #49](https://github.com/ARG-Digital-Solutions/aronexa-healthcare-platform/issues/49), [C7 #50](https://github.com/ARG-Digital-Solutions/aronexa-healthcare-platform/issues/50) | Global mutex superseded by scoped module/tenant/user lock protocol; cross-tenant concurrency acceptance | Global/scoped lock abuse, throughput and multi-resource deadlock |
| C8 | [Issue #51](https://github.com/ARG-Digital-Solutions/aronexa-healthcare-platform/issues/51) | Test-only ordered batch and bounded deadlock retry; audit invariants | Actual authorized administrator service and safe operational retries |
| C9–C10 | [Scan #52](https://github.com/ARG-Digital-Solutions/aronexa-healthcare-platform/issues/52), [remediation #53](https://github.com/ARG-Digital-Solutions/aronexa-healthcare-platform/issues/53), [C10 #54](https://github.com/ARG-Digital-Solutions/aronexa-healthcare-platform/issues/54) | Resolved Maven CycloneDX + OSV/npm scan; Spring Boot 4.1.2 patch clears 11 previously detected advisory matches | Future vulnerabilities, supply chain, scanner limits and threat-specific exposure |
| C11 | [Issue #55](https://github.com/ARG-Digital-Solutions/aronexa-healthcare-platform/issues/55) | Semgrep CE Java + TS source scan, zero rule matches at accepted SHA | Manual code review, deeper dataflow analysis, DAST |
| C12 | [Issue #56](https://github.com/ARG-Digital-Solutions/aronexa-healthcare-platform/issues/56) | Adversarial in-process HTTP auth/tenancy/malformed/idempotency tests | Deployed network and ingress attack paths |
| C13 | [Issue #57](https://github.com/ARG-Digital-Solutions/aronexa-healthcare-platform/issues/57) | Test-only bounded 4096-byte registration POST filter and JVM console synthetic canary | Proxy/body limits, slow client, multi-instance and external log sinks |
| C14 | [Issue #58](https://github.com/ARG-Digital-Solutions/aronexa-healthcare-platform/issues/58) | Disposable PostgreSQL 17 bind-value log suppression under explicitly safe settings | Original/deployed server settings; SQL literals, error text and telemetry |
| C15 | [Issue #59](https://github.com/ARG-Digital-Solutions/aronexa-healthcare-platform/issues/59) | Spring JDBC bind logger INFO default, memory-only TRACE positive control; 149 tests PASS | Runtime overrides, driver/ORM tracing, APM, support bundles and log access controls |

**Important:** All success statuses above refer to *internal, isolated synthetic execution*. No independent reviewer has executed or attested to any of them.

## 3. REV-01–REV-18: refreshed handoff matrix

**Independent review status for EVERY REV case: NOT RUN / NOT SIGNED.** The secondary column below reports relevant **internal test evidence only**, never a reviewer PASS.

| Scenario | Current internal evidence / limitation | Independent verdict |
|---|---|---|
| REV-01 SQL credential + forged session/tenant | Test-only SQL proof/tenant access negative cases | NOT RUN |
| REV-02 proof tampering/replay/expiry | Synthetic canonical proof, nonce/expiry tests | NOT RUN |
| REV-03 verifier rotation/retirement | Synthetic negative tests; real key lifecycle undecided | NOT RUN |
| REV-04 RLS/function/SQL writer privileges | Restricted-role test matrix; privileged owner compromise not assessed | NOT RUN |
| REV-05 role, branch, module revocation | C4–C7 both-order commit tests; unauthorized mutation inventory incomplete | NOT RUN |
| REV-06 token/session revoked during registration | C5/C7 concurrency evidence; commit-time expiry policy still open | NOT RUN |
| REV-07 reverse-order revocation and cancellation | Synthetic multi-connection tests; production admin deadlocks still possible | NOT RUN |
| REV-08 duplicate identity + idempotent retries | Synthetic duplicate/idempotency tests; real clinical linkage undecided | NOT RUN |
| REV-09 MRN uniqueness/rollback | Synthetic conflict/retry evidence; failover/restore not tested | NOT RUN |
| REV-10 authenticated rate limiter | Single-JVM tests; no distributed/persistent limit or pre-auth network defense | NOT RUN |
| REV-11 simultaneous signing + DB credential compromise | No real key custody/rotation or blast-radius proof | NOT RUN |
| REV-12 function owner/migrator/superuser compromise | Not safely modeled under an independently authorized threat scope | NOT RUN |
| REV-13 logging of patient/proof identifiers | C13 JVM console, C14 configured PostgreSQL, C15 JDBC logger defaults; proxies/APM/JDBC driver/SQL literals unverified | NOT RUN |
| REV-14 multiple replicas, session revocation and quota | Not implemented in synthetic-only in-memory limiter | NOT RUN |
| REV-15 backup/restore integrity of nonce/audit/MRN | Original Windows W3-E NO-GO/W4 APPLY BLOCKED | NOT RUN |
| REV-16 401/403/409/429/503/malformed/oversize | C12/C13 in-process HTTP tests; deployed proxy/client timeouts NOT tested | NOT RUN |
| REV-17 clinical privacy, consent, data lifecycle | Not independently reviewed or ready for actual PHI | NOT RUN |
| REV-18 disabled-by-default API and V10 migration | Fresh dev profile PASS; production image/runtime configuration NOT independently inspected | NOT RUN |

## 4. Critical gate owners / decisions required

| Gate | Required next action | Owner status | Release status |
|---|---|---|---|
| **G1 Independent reviewer** | Owner names qualified external application/DB security reviewer; obtains conflict declaration, scope, cost if any and authorized GitHub identity | **UNASSIGNED** | BLOCKED |
| **G2 Controlled access** | After specific owner approval, grant minimum access to sanitized repository/isolated disposable test env; no originals or real PHI | **NOT GRANTED** | BLOCKED |
| **G3 Independent REV tests** | Execute all REV-01–REV-18 with dated redacted reproducibility, code SHA, severity, mitigation and actual PASS/FAIL/NOT RUN | **NOT STARTED** | BLOCKED |
| **G4 Critical/high remediation** | Resolve and independently retest any critical/high findings and maintain clean SCA/SAST; no scanner bypass | **NOT ASSESSED INDEPENDENTLY** | BLOCKED |
| **G5 Deployment security design** | Decide key custody/rotation, distributed limits, privileged admin lock protocol, deployed ingress/telemetry logging, identity/security operations | **PROPOSED / PARTIALLY SYNTHETIC ONLY** | BLOCKED |
| **G6 Original Windows migration/restore** | Resolve Issue #31 evidence, require separate owner GO for W4 after backup/restore comparison and safety gates | **W3-E NO-GO / W4 BLOCKED** | BLOCKED |
| **G7 Clinical/privacy/release** | Clinical data handling, consent, compliance/privacy, recovery, customer acceptance and release owner signoff | **NOT APPROVED** | BLOCKED |

A reviewer completion **does not itself authorize W4, PR merge, C3 UI activation or customer release**. Those decisions remain separate and explicit.

## 5. Safe reviewer onboarding — only after named authorization

1. Reviewer supplies GitHub username or approved organization/team, applicable healthcare/Java/PostgreSQL experience, independence/conflict statement and review statement of work.
2. Owner verifies identity and explicitly approves **specific** least-privilege access. A generic "proceed" is **not** authorization to invite or modify repo access.
3. Pin the implementation SHA, create a clean checkout and disposable PostgreSQL 17 Testcontainers instance. Verify **no** original Windows V8/V9 DB connectivity, real patient data or production keys.
4. Reviewer executes the original 18-case worksheet and records FAIL/PASS/NOT RUN, artifact SHA, observed exploit setup and redacted evidence; opens tracked severity-ranked issues where needed. No raw tokens/patient names in GitHub.
5. Owner reviews independent findings, separately approves remediation and verification, and later decides on readiness to merge/deploy. Keep PR #38 draft until explicit merge decision.

## 6. Scope and attestation

**This C16 artifact is a snapshot and handoff index — not a threat assessment by an independent specialist, not a security certificate and not production/customer GO.** It must not be advertised as such.

If this file update creates a new head commit, run the existing PR Quality and Fresh Dev workflows at that new head and record results. Previous C15 application/test success remains tied to its original implementation commit until then.

**Permanent hold until authorized:** Issue #31 original W3-E/W4 database migration, PRs #32/#36/#38 unmerged, proposed V10 only in test resources, no real PHI, no receptionist C3 integration or deployment. **Overall P01-T07-C2 S2: OPEN / PRODUCTION NO-GO.**
