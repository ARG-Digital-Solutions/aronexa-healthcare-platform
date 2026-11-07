# ADR-S2-C2 — Production Security Design for Patient Registration

**Project:** AroNexa Healthcare Platform  
**Date:** 2026-11-07 IST  
**Status:** PROPOSED FOR INDEPENDENT REVIEW — NOT ACCEPTED / NOT IMPLEMENTED IN PRODUCTION  
**Owner authorization:** [Issue #44](https://github.com/ARG-Digital-Solutions/aronexa-healthcare-platform/issues/44)  
**Existing code review:** [Draft PR #38](https://github.com/ARG-Digital-Solutions/aronexa-healthcare-platform/pull/38)  
**Grounded source baseline:** \`8ee15b53b63879a16cd57f3a0d669dbdc8ae4f3a\`

## 1. Purpose and non-negotiable scope

Define production security decisions needed **before authorizing** the C3 receptionist UI/API integration. The current C2 function is a **synthetic-only proof of concept**, enabled only with Spring profile \`test & c2-synthetic & !dev & !prod\`, using a proposed V10 Flyway file under \`backend/src/test/resources/db/proposed/\`. None of the decisions below has been deployed. The original Windows V8 database, W3-E HOLD/W4 BLOCKED [Issue #31](https://github.com/ARG-Digital-Solutions/aronexa-healthcare-platform/issues/31), and draft PRs #32/#36/#38 must remain untouched and unmerged.

**Deployment choices are not yet settled.** AroNexa plans both an offline/single-site clinic edition and connected multi-tenant/cloud editions. Security topology, rate limiting, keys and backup requirements must be validated **per deployment mode**; do not assume a single architecture covers both.

## 2. Assets, actors and trust boundaries

| Asset/actor | Trust boundary and threats | Current state |
|---|---|---|
| Reception staff / bearer session | Stolen token, revoked staff, tenant/branch substitution | Filter validates opaque bearer; server retains session UUID; SQL verifies session with \`FOR SHARE\` in synthetic mode |
| Patient display name / DOB / MRN | Sensitive clinical identity, inference from logs, accidental cross-tenant access | Registration SQL sees data as arguments; UI has not been integrated; no real PHI authorized |
| Application signing key | Compromise can mint apparently valid proof; signer process memory/config/telemetry exposure | Ephemeral test-only HMAC secret, generated per disposable test fixture; no production custody design |
| SQL verifier key | Patient SQL login theft, privileged DB/migrator theft, backup restore leakage | Stored under restricted NOLOGIN writer access in synthetic V10; DB superuser/migrator remains a material trust boundary |
| Proof nonce / idempotency ledger | Replay, race, stale key and restore inconsistencies | Test-only transactional, unique nonce and bounded retry demonstrated |
| Database runtime roles and \`SECURITY DEFINER\` | SQL session GUC spoofing, RLS/grant bypass, search-path/extension tampering | Signed HMAC proof mitigates **stolen patient SQL login without signing key** in tested scope; no independent production assessment |
| Audits / metrics / backups | PHI or proof leakage, tampering, long retention, data loss | Synthetic metadata audit; operational controls and restore validation pending |
| Identity, role and module revocation | TOCTOU between check and commit; multiple transaction lock orders | Session row lock tested in both orders; membership/role/branch/module revocation races not fully serialized |

**Data flow in the existing synthetic design:** authenticated bearer -> Spring staff principal -> tenant/member/session/branch permission check -> server signs exact short-lived command with key ID, MRN, nonce -> restricted SQL runtime -> NOLOGIN-owned verifier function -> check HMAC + session/role/branch/module -> idempotent transactional patient+identifier+registration+audit write. A SQL-credential thief can set custom GUCs but cannot legitimately sign without further compromise.

## 3. Decision register — options requiring explicit security review

### SEC-D01 — Production proof signing and verifier key custody (HIGH, OPEN)

**Option A:** Keep HMAC but store signing keys in a restricted secret manager and provision a separate verifier secret under tightly scoped, audited DB privileges. Costs: verifier DB still holds secret; DB owner/superuser and anyone with signer key can forge. Require documented secret provenance, staging rotation, key expiration, access logs, emergency revocation and backup handling.

**Option B:** Evaluate **asymmetric, versioned authorization proofs** with signing private key outside SQL DB and public key verifier inside DB. This may reduce impact of DB verifier-key read compromise, but only if a supported, audited PostgreSQL verifier/extension and safe canonicalization are proven, independently reviewed, and operationally supportable. Do **not** assume stock PostgreSQL pgcrypto provides the needed verification algorithm or install unreviewed extensions.

**Provisional position:** No production choice approved. Perform threat-model and cryptographic implementation review before selecting. Do not migrate the synthetic HMAC design unchanged. The one-use nonce and short expiry remain requirements under either choice.

Required acceptance: key ID binding, signature manipulation, freshness, nonce replay, retired/compromised key, signer compromise, verifier compromise, cold start without key, crash recovery, dual-key overlap, rotation/revocation rollout, protected transport and evidence of exactly who can access each key. **No key material in Git, logs, Docker images or customer backups.**

### SEC-D02 — Authentication and authorization linearization (HIGH, OPEN)

Current synthetic function uses an authenticated \`auth_session.id\` signed in HMAC proof V3 and \`FOR SHARE\` on that row. Real PostgreSQL tests show serialized ordering for logout versus registration, but **permission/role/branch/module rows are read without an equivalent revocation lock protocol**. Token expiration is checked at SQL operation time, not necessarily again at commit.

**Decision needed:** Choose and document one user-visible policy: a registration already authorized and committed before logout may succeed; logout blocks any registration that reaches the authorization gate afterward. Specify a transaction lock order across session, membership, role grants, branch assignments and entitlements—or an equivalent versioned authorization epoch—and test revocation wins/registration wins. Specify whether token expiration at commit requires cancellation (and how long transactions may run). Prevent deadlocks and pooled connection context contamination.

Required tests: disable user/membership, revoke \`PATIENT_REGISTER\`, unassign branch, disable module, expire token, logout/password change, crossed tenants, long-running transaction; both commit orderings, post-restart behavior, and no partial writes. Independent reviewer must validate design and evidence.

### SEC-D03 — Centralized admission / abuse controls (HIGH, OPEN)

Synthetic in-memory admission is **12 requests/60 seconds per staff and 120/60 seconds per tenant within one JVM**. It cannot enforce a global quota across replicas, and does not address login attacks or client/network resource limits.

**Cloud/multi-replica candidate:** centralized atomic quota with bounded memory, tenant-safe keys, and clear fail-closed behavior on datastore unavailability. Redis with authenticated TLS and Lua/server-side atomic decisions is a candidate **not approved or provisioned**; a PostgreSQL-based or dedicated gateway option must also be evaluated for complexity, availability and offline deployment.

**Offline/single-node candidate:** local centralized admission can be sufficient only after proving a *single process* boundary, safe restart/reset policy, local network exposure, no untrusted bypass route, and explicit hardware/device limits. Multi-user offline LAN setups still need abuse protections.

Required tests: two or more replicas; 100+ concurrent requests; shared staff quota across branches and instances; tenant isolation; auth endpoint throttling; failed cache/Redis; clock skew; TTL/eviction; oversized/slow body; 429+Retry-After; alerts free of patient identifiers.

### SEC-D04 — SQL observability, data minimization and secrets (CRITICAL, OPEN)

Function parameters include **patient name, DOB precision/date, MRN and signed proof**. Parameterized JDBC prevents literal concatenation, but DB statement/error logging, driver debug, APM tracing, SQL proxies and exception messages could still expose values.

Before staging with patient-like records, inventory actual configurations for \`log_statement\`, \`log_min_error_statement\`, parameter logging, proxy/query collectors, transaction traces, distributed tracing, exception serialization and crash dumps; demonstrate redaction in each path and test log access controls, retention and deletion. Require TLS and service-to-database identity control. **Never log bearer tokens, proof MACs, key bytes, full names, DOBs or unrestricted MRNs.** Use synthetic canary strings and verify they cannot appear in telemetry exports. This design does not assert that current production logging is safe.

### SEC-D05 — Database roles, RLS and function ownership (HIGH, OPEN)

Review test-only V10 \`SECURITY DEFINER\`, FORCE RLS, \`EXECUTE\` grants, role owner/no-login, \`search_path\`, temp schema/extension privileges, \`pgcrypto\` dependencies, function argument canonicalization, all tenant-scoped constraints and restore-time permissions. Specifically investigate **column-limited UPDATE(revoked_at) on \`auth_session\` granted to NOLOGIN writer** to permit \`SELECT FOR SHARE\`; justify or reduce it so the function cannot unexpectedly change sessions. Test stolen patient/auth/app SQL logins, connection resets, DDL privilege escalation, SQL injection, role inheritance and owner/migrator compromise.

A successful synthetic check is **not** permission to apply V10 to persistent data or to run Flyway against the original V8 database.

### SEC-D06 — Backups, recovery, idempotency and MRN (HIGH, OPEN)

Determine expected RPO/RTO for single-clinic offline and connected hosted deployment separately. Restore rehearsals must preserve tenant isolation, MRN unique constraints, idempotency and one-use nonce history; restored copies must have environment-specific keys and secrets. Validate encrypted backups, key-loss scenarios, old-key retirement, replay after restore, audit tamper detection, safe migrations, and operational alerting when bounded MRN retry exhausts (generic response, no patient details).

### SEC-D07 — Patient data, clinic workflow and deployment gate (HIGH, OPEN)

The synthetic demo has no approved real-data processing. Confirm least privilege, clinical consent/patient photo/report access, privacy/retention rules, patient identity review versus auto-merge, safe print/share/audit policies and jurisdiction-specific legal review. A security engineer's signoff does not replace a clinical owner, privacy review, Windows offline acceptance or separate customer release approval.

## 4. Evidence and reviewer sign-off required

| Gate | Current decision | Approval needed |
|---|---|---|
| Design and threat model | This ADR proposes options; none production approved | Independent security architect |
| Production signer custody & crypto verification | NOT IMPLEMENTED | Security/DevOps owner; cryptographic review |
| Session/role/module revocation rules | Partial synthetic tests only | Identity, DB/security reviewers |
| Distributed/host-specific rate limiting | NOT IMPLEMENTED | Platform/security owners |
| Telemetry/PHI redaction | NOT VERIFIED | Observability/privacy reviewers |
| Privilege/RLS, restore and key recovery | NOT INDEPENDENTLY REVIEWED | Database/security/operations reviewers |
| Customer / C3 UI integration readiness | BLOCKED | Explicit software owner authorization after evidence |
| Original Windows W3/W4 migration | HOLD / BLOCKED | Separate original-database owner disposition |

**Independent reviewer:** Unassigned. **Date/signature:** Pending. **Owner decisions:** Pending. **Final disposition:** NO_GO for real patients, production, persistent V10, C3 UI integration, or customer release.

## 5. External verification references

- [OWASP ASVS 5.0.0 stable](https://asvs.owasp.org/downloads/) — use a pinned stable version rather than mutable bleeding-edge requirements.
- [OWASP API Security Top 10 (2023)](https://owasp.org/API-Security/editions/2023/en/0x00-header/) — particularly object/role authorization and resource consumption.
- [PostgreSQL 17 CREATE FUNCTION / SECURITY DEFINER guidance](https://www.postgresql.org/docs/17/sql-createfunction.html).
- [PostgreSQL 17 privileges](https://www.postgresql.org/docs/17/ddl-priv.html).

**This artifact is a design proposal and review checklist, not independent approval or a security certification.**