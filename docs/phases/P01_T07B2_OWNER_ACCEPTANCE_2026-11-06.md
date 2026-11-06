# P01-T07-B2 — Formal owner-directed internal-foundation acceptance

**Date:** 2026-11-06 IST
**Task:** P01-T07-B2 — dedicated patient SQL runtime, V9 RLS, registration-ID read and actor-attributed access audit.
**Reference:** [draft PR #30](https://github.com/ARG-Digital-Solutions/aronexa-healthcare-platform/pull/30).
**Implementation SHA reviewed:** `edb7b471ddd80a9dbcf2e36fed189756d2f6f3df`.

## 1. Owner's instruction and scope of decision

The owner instructed on 2026-11-06 IST:

> eview and formally accept the B2 internal foundation, then separately authorize a controlled Windows V8-to-V9 migration with verified backup and disposable rehearsal.

This is an explicit instruction to **record formal owner-directed acceptance of the limited internal foundation**. It does not constitute an independent security certification, PR merge authorization, patient data release or unconditional permission to run migrations on the original database.

**Decision:** `OWNER_ACCEPTED / TASK CLOSED — P01-T07-B2 INTERNAL_FOUNDATION only`, within evidence and limitations below. This supersedes prior statements that owner acceptance was pending, but leaves security/release limitations open. The task implementation is committed to PR #30, not merged.

The separately authorized *migration activity* is documented and tracked independently and is subject to all evidence and safety gates. It is **not represented as executed** by this owner decision.

## 2. Implementation accepted

- New nonowner/non-BYPASSRLS patient SQL role, separate application/authentication/patient JDBC pools and dedicated patient transaction manager.
- Forward-only V9 schema/privilege changes: disabled `PATIENT_REGISTRATION` module and permission definitions without role assignment, membership/tenant-scoped policies, registration-ID reads restricted to assigned branch, immutable actor-attributed successful-read audit.
- Internal-only patient lookup and branch authorization. No HTTP exposure or patient master/contact data access.
- Fresh isolated dev bootstrap provisions the fourth account on a **new** volume only; owner persistent V8 volume unchanged.
- Existing V1–V8 migrations remain immutable.

## 3. Executed technical acceptance evidence

| Acceptance gate | Finding |
|---|---|
| [GitHub PR Quality #42152283374](https://github.com/ARG-Digital-Solutions/aronexa-healthcare-platform/actions/runs/42152283374) | **PASS**; 83 Maven tests, 0 failures, 0 errors, 0 skipped; frontend build and documentation/scope/secrets checks PASS |
| [GitHub Fresh Dev Runtime #42152283376](https://github.com/ARG-Digital-Solutions/aronexa-healthcare-platform/actions/runs/42152283376) | **PASS**; fresh disposable PostgreSQL V9 migration, four roles and expected privilege assertions |
| New patient tests | **11 included in successful suite**: 6 access-service and 5 dedicated runtime/RLS tests |
| Independent Windows V8 backup/restore/rehearsal | **NOT RUN** |
| Original persistent V8->V9 migration | **NOT RUN** |
| Independent security/penetration review | **NOT DONE** |
| Customer or real-patient acceptance | **NOT DONE** |

These are GitHub CI results, not independently conducted local Windows trials.

## 4. Open risks acknowledged, not waived

1. PostgreSQL custom transaction GUCs are set by server code but **not cryptographic proof** of a tenant or actor to the DB; direct compromised patient-role SQL can spoof tenant/membership, subject to other permissions. Independent SQL-credential/network isolation and additional anti-spoofing controls require review before actual patient data.
2. The authentication runtime still has broad account/session privileges; MFA, verified recovery, throttling, monitoring and access provisioning are incomplete.
3. V8 `patient_contact.contact_value` remains plaintext; patient role has no contact privileges and **no** real contacts or patient information may be stored.
4. Read-access audit covers successful internal registration-ID reads, **not** complete create/update/rejection/clinical audit.
5. No MRN allocator, patient insert/write path, idempotency, duplicate-detection, portal, guardian verification, UI, patient-facing API, or customer rollout.
6. The module is disabled/unreleased by default with no tenant entitlements; no public routes opened.
7. PR #30's code requires further independent security review and separate merge approval. Prior PR #27 merge discrepancy remains tracked in [issue #28](https://github.com/ARG-Digital-Solutions/aronexa-healthcare-platform/issues/28).
8. No existing-volume Windows V8->V9 rehearsal or backup SHA attestation has been reviewed. Existing V8 acceptance script has a known Apply manifest-property issue; **do not rerun it**.

## 5. Final separation of decisions

| Decision | Status |
|---|---|
| B2 limited task-level acceptance | **OWNER_ACCEPTED / CLOSED — INTERNAL_FOUNDATION** |
| PR #30 merge to main | **NOT AUTHORIZED**, remains DRAFT |
| Controlled V8->V9 Windows migration procedure | **Separately authorized to prepare and execute gated preflight/backup/disposable rehearsal**; original apply is not permitted until recorded PASS evidence and owner go/no-go as specified in separate migration record |
| Original persistent Windows V8 database altered | **NO** |
| B3 registration implementation | NOT AUTHORIZED by this decision |
| Full Phase 01 closure, customer deployment, real-patient use | **BLOCKED** |

Owner-directed task acceptance is an engineering milestone only. A change in these restrictions requires separate explicit owner approval and supporting evidence.
