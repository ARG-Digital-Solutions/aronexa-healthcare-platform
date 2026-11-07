# P01-T07-C2-S2 — Registration API Security Hardening & Closure Register

**Owner instruction:** Start P01-T07-C2-S2.  
**Date:** 2026-11-07 IST  
**Authorization:** [Issue #40](https://github.com/ARG-Digital-Solutions/aronexa-healthcare-platform/issues/40)  
**Review PR:** [Draft #38](https://github.com/ARG-Digital-Solutions/aronexa-healthcare-platform/pull/38)  
**Classification:** INTERNAL SYNTHETIC SECURITY FOUNDATION — NOT APPROVED FOR PRODUCTION OR CUSTOMER/PATIENT USE.

## Implemented S2-A increment

1. **Authenticated request admission.** Spring MVC handler interceptor on exactly `POST /api/v1/branches/{branchId}/patient-registrations` executes after Spring Security's bearer authentication, **before** the JSON body is parsed and the controller is invoked. It derives tenant/membership from the verified `AuthPrincipal`, never tenant, branch or session headers.
2. **Per-staff and per-tenant quotas.** A synchronized, fixed-window in-memory guard allows at most **12 requests per 60 seconds per (tenant,membership)**, and **120 per 60 seconds per tenant**. A staff member cannot bypass the limit with another branch URL or forged headers; one tenant's budget cannot exhaust another tenant's.
3. **Fail-closed bounded state.** Tracks at most 4,096 staff scopes and 1,024 tenant scopes. Live counters are never evicted to admit extra traffic. Expired counters are safely reaped. The check-and-increment are atomic in one process. The return is generic `429` with `Retry-After: 60`, `Cache-Control: no-store` and no patient/branch details.
4. **Test-only activation.** The interceptor, controller and registration service all require `test & c2-synthetic & !dev & !prod`. No production endpoint is enabled and the normal dev runtime remains deny-by-default.
5. **Negative tests.** Independent pure unit tests for quota, expiry, staff and tenant separation, bounded memory and concurrent calls; MockMvc + real PostgreSQL Testcontainers test for 12 malformed authenticated requests that still count, followed by safe `429`, forged header handling and zero patient writes; SQL LOGIN tests for patient/app/auth role denial to the verifier secret and nonce ledger.
6. Existing C2/S1 tests for signed request proof, one-use nonce, SQL credential/GUC spoofing, tenant/branch/module/permission checks, idempotent/concurrent registration and audit must continue to pass.

**Limit:** This limiter applies only after successful bearer authentication; malformed login attacks require separate auth-endpoint controls. It is an in-memory *single JVM* synthetic gate, **not a load-balanced/multi-replica production rate limiter**. It does not prove a durable, multi-instance rate limit or secure a stolen app signing key.

## Acceptance (evidence must come from latest code HEAD)

| Gate | Scope | Required |
|---|---|---|
| S2-A01 | HTTP 12 requests then generic 429, 60-second reset | Run Testcontainers + unit tests |
| S2-A02 | Staff / tenant quotas and bounded concurrent accounting | Run unit tests |
| S2-A03 | Invalid payloads count; bearer 401 and permission 403 unchanged | Run full MVC suite |
| S2-A04 | Patient/app/auth SQL LOGIN cannot read proof secret/nonce, nor write | Run isolated SQL role tests |
| S2-A05 | V1–V9 immutable; V10 only in test resources; standard runtime default-deny | Diff audit + fresh dev CI |
| S2-A06 | Existing C2 registration, proof, concurrency, frontend and regression | Full CI |
| S2-A07 | Scope, secret and documentation checks | Full CI |
| S2-A08 | Owner security review and formal close | **NOT AUTHORIZED / NOT COMPLETE** |

This document must **not** be interpreted as a PASS report: attach exact GitHub Actions run URLs, commit SHA, result totals and any failures to [Issue #40](https://github.com/ARG-Digital-Solutions/aronexa-healthcare-platform/issues/40) after completion.

## Remaining S2-B / production security blockers

- **Revocation race:** check authenticated session validity at the exact registration transaction boundary, rather than only when bearer is parsed. Define how logout, membership disable, role/branch revocation and key rotation interact with in-flight commands.
- **Proof key lifecycle:** protect with a dedicated secret store outside general database/SQL logging; design rotation, dual-key overlap, exact signer key-ID binding, zero-downtime retirement, compromise recovery and rollback. Test stolen application key, SQL database owner and logging exposure; current isolated protection only covers stolen patient SQL LOGIN without app key.
- **Collision and retry:** handle a deliberately forced MRN uniqueness conflict with bounded new transaction and new proof, avoiding orphan patient rows, nonce reuse, misleading success and duplicate identity.
- **Distributed anti-abuse:** central atomic quota across all instances/tenants, pre-auth login throttling, request-body byte limits, timeout/slow-client protection, alerting without PII, safe retry signals and negative load tests.
- **SQL hardening:** independent `SECURITY DEFINER` / RLS/GRANT schema audit, constraint and policy fuzz tests, extensions/search-path review, safe use of connection pools and read-only verifier key.
- **Regulatory/compliance and operations:** personal-data minimization, backups and restore, retention/incident handling, clinical audit acceptance, vulnerability scans, penetration testing, Windows offline acceptance and release runbook.

## Hard gate

**Do not merge PR #38 or PR #36; do not start UI integration C3; do not enable API under dev/prod or use patient data; do not apply V8→V9 W4 or V10 to the original Windows database.** Issue #31 W3-E NO-GO and W4 original database migration BLOCKED remain binding. PR #32 also remains draft/unmerged.

Only independent security review plus separate explicit owner approval can unlock a subsequent integration release gate.
