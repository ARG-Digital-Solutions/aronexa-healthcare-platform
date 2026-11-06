# P01-T07-B2-MIG — W3-E Formal Owner GO/NO-GO Decision Record

**Prepared:** 2026-11-06 IST  
**Scope:** Original **synthetic-only** Windows development PostgreSQL 17, database `aronexa_dev`, Docker Compose project `aronexa-dev`, existing original Flyway **V8**, proposed forward-only Flyway **V9**.  
**Evidence register:** issue [#31](https://github.com/ARG-Digital-Solutions/aronexa-healthcare-platform/issues/31), [W3 Formal Evidence Review](P01_T07B2_W3_FORMAL_EVIDENCE_REVIEW_2026-11-06.md), [W3-D exception assessment](P01_T07B2_W3D_GOVERNANCE_EVIDENCE_EXCEPTION_REVIEW_2026-11-06.md), merged PR [#30](https://github.com/ARG-Digital-Solutions/aronexa-healthcare-platform/pull/30), draft PR [#32](https://github.com/ARG-Digital-Solutions/aronexa-healthcare-platform/pull/32), governance issue [#28](https://github.com/ARG-Digital-Solutions/aronexa-healthcare-platform/issues/28).

## 1. Decision status — **NOT AUTHORIZED**

| Decision field | Status |
|---|---|
| Technical reviewer recommendation | **NO-GO / HOLD** for W4 original Flyway V8→V9 Apply |
| **Actual owner-signed W3-E GO/NO-GO** | **PENDING — no explicit owner choice was provided for W3-E** |
| Evidence sufficiency for unconditional W4 GO | **NOT ESTABLISHED** |
| W4 original Apply | **BLOCKED — no migration authorization** |
| W5 original acceptance | NOT STARTED |
| PR #32 | DRAFT / UNMERGED, **DO NOT MERGE** |
| PR #30 | Already merged accidentally, **provisionally retained**, final owner retention approval PENDING |
| Customer/real patient release | STRICTLY BLOCKED |
| Scope of previously accepted B2 task | INTERNAL_FOUNDATION ONLY; remains limited task acceptance |

**Important:** This record **does not impersonate an owner signature, silently choose NO-GO for the owner, or grant W4 GO**. The status is a documented **reviewer recommendation of NO-GO**, with the actual **owner W3-E decision still pending**. Pending approval means operational HOLD. The owner must make a separate, explicit signed choice in issue #31; absence of a response is not approval.

## 2. Evidence considered

| Gate | Evidence and provenance | Evaluation |
|---|---|---|
| W0 V8 preflight | Previously operator-reported read-only PASS; original contemporaneous logfile not located in examined inventory | **REPORTED PASS / ORIGINAL EVIDENCE GAP** |
| W1 backup | Operator-reported PostgreSQL custom dump 92,567 bytes; SHA-256 `153842CCC811A64729F2DBA0743515A6C24E5A20AFB1D054AF054FECB36C3CAB`, `pg_restore --list` and source/host hashes reportedly PASS; raw source/host transcript not located | **REPORTED PASS / PROVENANCE GAP** |
| W1/W2D 25-table baseline | Operator-reported W3-B comparison: EARLY_W1=25, LATER_W1=25, W2D=25, normalized names+counts match exactly; 533- and 637-byte raw CSV size difference not a data-count difference | **W3-B OWNER-REPORTED PASS** |
| W2A disposable V8 restore | Original schema, roles and all 25 table counts reportedly match; restored original execution log not located | **REPORTED PASS / ORIGINAL EVIDENCE GAP** |
| W2B V9 isolated migration | Flyway validate/migrate/validate and W2B read-only postcheck reportedly PASS; original migration transcript not located | **REPORTED PASS / CONTEMPORANEOUS LOG GAP** |
| W2C synthetic RLS and rollback | `W2C_SYNTHETIC_ISOLATION_AND_ROLLBACK_PASS`, operator exit 0. Repository W2C script source explicitly checks required scenario markers, transaction-local reset, ROLLBACK, and read-only V9 postcheck; original per-scenario console output not retained in examined inventory | **REPORTED PASS / PER-SCENARIO EXECUTION GAP** |
| W2D sanitized ZIP | Operator-reported 3 ZIP entries, two SHA-manifest-listed files, validated outer SHA-256 `9BEF04CBA2FC7E5DAF790E34521538558713B8A0B164979FE52ED7E011C9BE97`. Contents are summary/CSV/manifest, *not* original event logs | **OWNER-REPORTED INTEGRITY PASS, LIMITED CONTENT** |
| W3-C retrospective read-only | `W3C_READ_ONLY_V9_INTEGRITY_PASS`, operator PASS; retained report hash `134A13AF651151391DF86F138C772ECF079FFDDE785A9679D721A7DDB3841A01`, W3-D re-hash PASS. Report bytes and full contents not independently reviewed | **OWNER-REPORTED PASS / NOT CONTEMPORANEOUS W2B** |
| GitHub engineering checks | PR #30 merged `35c583e05f3199c66f830ecabc4a38b16004f98b`; CI for its B2 code previously passed. PR #32 documentation/acceptance work remained DRAFT. GitHub workflows are fresh-code verification, *not* Windows original migration evidence | **REPO-VERIFIED, SCOPE LIMITED** |
| Governance | Owner acknowledged PR #30 was merged accidentally. GitHub metadata: 2026-11-06T08:36:25Z; author account `argsolutions2017`, committer `web-flow`; 0 PR #30 submitted reviews. Screenshot of GitHub ruleset displays private organization rulesets not enforceable on present plan | **ACKNOWLEDGED ACCIDENT / FINAL DISPOSITION OPEN** |

The specific V9 SQL migration Git blob reviewed in W3 was `3a1e2b717576ccd84f0de058bb0a83786c517d32`. No new independent local verification of the V9 blob, original V8 state or original W1 dump was performed while preparing this record.

## 3. W3-D exceptions — formal disposition proposed

| ID | Exception | Recorded disposition | Impact on W4 |
|---|---|---|---|
| EX-01 | Original W0/W1/W2A receipts and source-to-host backup provenance unavailable for independent inspection | **OPEN — NOT ACCEPTED AS FULLY PROVEN.** Retrospective baseline and backup checks are supporting evidence, not original provenance | Required evidence/explicit risk disposition pending |
| EX-02 | Original W2B Flyway migrate/validate transcript not located | **OPEN — REVIEW LIMITATION.** Later W3-C read-only results attest current isolated state but do not recreate the historical migration operation | Owner must explicitly accept limitation after independent review, or provide surviving records |
| EX-03 | Original W2C per-scenario/rollback transcript not located | **OPEN — REVIEW LIMITATION.** W2C source defines 15 scenario markers and rollback/postcheck gates; previous operator final PASS is not the raw transcript | Owner must explicitly accept limitation or separately authorize a new synthetic-only rollback test with contemporaneous private capture |
| EX-04 | PR #30 merged despite the separate merge approval hold | **PARTIALLY RESOLVED — ACCIDENT ACKNOWLEDGED; FINAL RETENTION/PROTECTION DECISION OPEN**. PR #27 similarly tracked in issue #28 | Document owner retention choice and safeguards; no automatic revert |
| EX-05 | Adversarial security, patient-data, clinical acceptance not performed | **OPEN / OUTSIDE SYNTHETIC MIGRATION CERTIFICATION; REAL-PATIENT RELEASE BLOCKER** | Not necessarily a synthetic-only schema migration failure; must never be waived for production/patients |

**Exception handling:** Missing records are **not found in the reviewed local inventory**, not proven nonexistent everywhere. No record is fabricated, backdated or relabeled as original historical execution evidence. None is marked risk-accepted by the owner through this record.

## 4. Explicit blockers before any future W3 GO

- [ ] Independently inspect a **sanitized** excerpt of the retained W3-C report for required acceptance and version/role/RLS/module audit markers, and confirm SHA against retained private original.
- [ ] Independently review/source-attest the original W1 backup provenance (original-vs-host digest, `pg_restore --list`, restore identity and 25-table counts), or clearly document and accept each residual limitation.
- [ ] Determine whether a contemporaneous W2C transcript exists outside the inventoried backup directory; if not, owner explicitly records acceptance/rejection of that limitation and optionally authorizes **separate synthetic-only** re-verification. No implicit authorization to rerun anything.
- [ ] Document owner decision to retain or separately correct PR #30, and actual enforcement limits of `Protect-main`; clarify PR #27 issue #28 governance recurrence.
- [ ] Recheck **at owner decision time**, with a reviewed **read-only** procedure, original `aronexa_dev` identity, synthetic-only state, Flyway V8 and verified W1 SHA; confirm exact V9 source Git blob and roles. After GO, W4 itself still requires separately reviewed preflight and no automatic migration.
- [ ] Independent reviewer records a W3 readiness conclusion consistent with the **limited synthetic-only** scope.
- [ ] Owner issues explicit, separately dated **W3-E GO** referencing issue #31, exact evidence hashes and the signed accepted exceptions. W3-E GO is a new authorization event; **this preparation is not GO**.

## 5. Owner decision section — unsigned

**W3-E owner decision:** `PENDING`  
**Recommended choice:** `NO-GO / HOLD` until the above gaps are addressed  
**Owner's explicit choice:** `NOT PROVIDED`  
**Decision timestamp:** `NOT PROVIDED`  
**Owner comment URL:** `NOT PROVIDED`

If the owner wishes to formalize the present conservative decision **without authorizing W4**, the owner should post the following separately in [issue #31](https://github.com/ARG-Digital-Solutions/aronexa-healthcare-platform/issues/31):

> **I explicitly decide W3-E NO-GO / HOLD for P01-T07-B2-MIG as of 2026-11-06. I acknowledge W3-B/W3-C read-only PASS, the W2D archive integrity reports, unresolved W3-D evidence exceptions EX-01 through EX-05 and my accidental PR #30 merge. I do not authorize W4 migration of the original Windows V8 database, PR #32 merge, patient/customer release, or deletion of private evidence. A new GO requires a separate documented review and authorization.**

**This text is a proposed owner declaration, not an actual owner statement. Do not post it as if the owner signed it.**

Any future affirmative GO must be explicitly requested and evaluated anew; do not infer it from this record or an earlier B2 INTERNAL_FOUNDATION acceptance.

## 6. Current safety constraints

- Preserve original Windows V8 volume, W1 verified private backup, W2 isolated V9 (if retained), W2D archive and W3-C report.
- Do not start a V9 application against the original Windows V8 datasource with automatic Flyway migration enabled.
- Do not execute W4 Apply, Flyway migrate/repair/clean, original role bootstrapping, original restore, Docker volume deletion or disposal of the rehearsal.
- Do not merge PR #32 or deploy; no customer or real-patient data.

**W3_E_REVIEW_RECOMMENDATION:** `NO_GO_HOLD`  
**W3_E_OWNER_DECISION:** `PENDING_EXPLICIT_OWNER_SIGNOFF`  
**W3_OWNER_GO:** `NOT_GRANTED`  
**W4_ORIGINAL_APPLY:** `BLOCKED`  
**PR32_MERGE:** `HOLD`  
