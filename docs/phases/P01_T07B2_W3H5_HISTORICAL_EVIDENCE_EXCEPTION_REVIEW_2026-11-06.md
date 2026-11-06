# P01-T07-B2-MIG — W3-H5 Formal Historical Evidence Exception Review

**Date:** 2026-11-06 IST  
**Task/Scope:** Original Windows PostgreSQL 17 `aronexa_dev`, synthetic development only, planned V8→V9 controlled migration.  
**Source register:** Issue [#33](https://github.com/ARG-Digital-Solutions/aronexa-healthcare-platform/issues/33), owner W3-E [NO-GO/HOLD declaration in issue #31](https://github.com/ARG-Digital-Solutions/aronexa-healthcare-platform/issues/31#issuecomment-6690270572), [W3-D review](P01_T07B2_W3D_GOVERNANCE_EVIDENCE_EXCEPTION_REVIEW_2026-11-06.md), [formal W3-E decision](P01_T07B2_W3E_OWNER_DECISION_RECORD_2026-11-06.md).  
**Work authorized for W3-H5:** Source/repository review and documentation of evidence exceptions **only**, without SQL, Docker/database changes, replay, merge, revert, deployment or release.

## 1. Review conclusion

**W3-H5 review status: COMPLETED_WITH_OPEN_EXCEPTIONS.** This is completion of *classification and formal documentation*, not proof that the original migration/restore/test events were independently verified and not owner acceptance or closure of evidence exceptions.

**W3-E OWNER DECISION:** `NO_GO_HOLD_APPROVED_2026-11-06`  
**W3 OWNER GO:** `NOT_GRANTED`  
**W4 ORIGINAL DATABASE MIGRATION:** `BLOCKED`  
**PR #32:** `DRAFT / DO_NOT_MERGE`  
**CUSTOMER / REAL-PATIENT RELEASE:** `BLOCKED`

Do not interpret task-level B2 INTERNAL_FOUNDATION acceptance, successful isolated CI, retrospective read-only checks, or this exception review as a GO for original Flyway V9 Apply.

## 2. Evidence reviewed and provenance distinctions

The owner provided sanitized PowerShell output; private backup and log files were **not transferred for independent content inspection**. GitHub issue evidence, code and governance records were inspected separately.

| Review | Concrete evidence | Reviewer disposition and limit |
|---|---|---|
| W3-B | Early W1, later W1 and W2D baseline CSV each **25 unique tables**; normalized table names/counts match | **PASS owner-reported**; establishes CSV semantic equivalence, not a contemporaneous source DB snapshot/transfer event |
| W3-C | Current read-only original V8/disposable V9 checker returned `W3C_READ_ONLY_V9_INTEGRITY_PASS`; retained private report SHA-256 `134A13AF651151391DF86F138C772ECF079FFDDE785A9679D721A7DDB3841A01` | **PASS owner-reported** for retrospective read-only integrity, not original Flyway migrate/validate output |
| W3-H1 | `W3H1_MISSING_MARKERS: 0`; `W3H1_RETAINED_MARKERS_VERIFIED` | **PASS owner-reported**; ten expected W2B read-only markers in retained W3-C report, not original W2C per-scenario output |
| W3-H2 | **1** retained backup, **92,567 bytes**, SHA-256 `153842CCC811A64729F2DBA0743515A6C24E5A20AFB1D054AF054FECB36C3CAB`; `W3H2_LOCAL_BACKUP_INTEGRITY_PASS` | **PASS owner-reported** for current stored Windows file; not original container/host transfer receipt |
| W3-H3 | Docker/PG17 offline read-only `pg_restore --list`, **25 TABLE definitions + 25 TABLE DATA entries**, `W3H3_OFFLINE_ARCHIVE_STRUCTURE_PASS` | **PASS owner-reported** for archive header/index metadata; does not prove all archive data blocks are restorable. An earlier faulty regex counted both types as 50; fixed mutually exclusive patterns resolved this. |
| W3-H4 | Search in **5 existing of 6 configured roots** returned **10 metadata candidates** modified since 2026-11-05; all ten classified as **9 checkout documentation files + 1 W2D SHA manifest**; **0 original W0–W2C execution logs identified** | **INVENTORY COMPLETE within filter/search scope**; not proof such logs never existed elsewhere or inside an excluded filename, path, timestamp or extension |
| Earlier W2A/W2B/W2C | Operator previously reported isolated V8 restore/count/ACL PASS; Flyway V9 migrate/validate and W2B read-only PASS; W2C synthetic isolation/rollback script exit 0 and `W2C_SYNTHETIC_ISOLATION_AND_ROLLBACK_PASS` | **HISTORIC OPERATOR-REPORTED PASS**, not independent inspection of contemporaneous full command output |
| W2D archive | 3-entry sanitized ZIP, 2 SHA-manifest files, ZIP SHA-256 `9BEF04CBA2FC7E5DAF790E34521538558713B8A0B164979FE52ED7E011C9BE97` owner-reported verification | **RETAINED/INTEGRITY PASS owner-reported**; archive intentionally does **not** contain original W0–W2C console transcripts |

**Source code versus execution:** The version-controlled W2C SQL/wrapper is designed to assert **15 named synthetic access/permission scenario markers**, plus transaction rollback/reset and a post-rollback read-only W2B verifier. This design review is not independent evidence that all 15 original checks produced PASS at the historical run time. No re-execution is authorized by W3-H5.

## 3. Formal exception-by-exception decision

### EX-01 — Original backup creation, transfer and V8 restore provenance

**Current state: OPEN / PARTIALLY MITIGATED.** W3-H2 and H3 provide current-file hash, size and PostgreSQL archive metadata. W3-B reconciles 25-table baseline CSVs, and W2A restore PASS was previously reported. The original source-container hash/copy event, timestamped `pg_restore --list` and isolated V8 restore/role/ACL transcripts were **not located in the bounded search**.

**Evidence disposition:** Classify historical transfer/restore receipts as `NOT_LOCATED_IN_SEARCH_SCOPE`. Do not fabricate them, assume the original container matched the current stored file based on later hash alone, or imply they were independently reviewed.

**Conditions to reconsider:** Independent reviewer inspects any subsequently found genuine original receipts; if unavailable, explicitly evaluates the residual loss of provenance and the need for any new *separately authorized* independent verification. No restore is authorized here.

### EX-02 — Original W2B V8→V9 isolated migration transcript

**Current state: OPEN / RETROSPECTIVE TECHNICAL MITIGATION.** Later W3-C read-only V8/V9 verification, H1 ten markers, unchanged V1–V8 checksum assertions and exact expected V9 row deltas are strong *current-state* supporting evidence. They do **not** reconstruct original Flyway command order, V9 migration console output or past failure recovery.

**Evidence disposition:** `CONTEMPORANEOUS_MIGRATION_TRANSCRIPT_NOT_LOCATED`. Accept current checks only within their retrospective scope; any risk acceptance or sufficient-evidence conclusion must be separately reviewed and approved.

### EX-03 — Original W2C RLS and rollback execution transcript

**Current state: OPEN.** The operator previously reported final W2C PASS/exit 0. Repository sources define 15 SQL scenario markers, explicit ROLLBACK and transaction-context reset, with a read-only postcheck. The original per-scenario output was not found.

**Evidence disposition:** `CONTEMPORANEOUS_SCENARIO_OUTPUT_NOT_LOCATED`. Do not claim the 15 original results were independently verified solely by reading the test source.

**Options for future scoped decision:** (A) preserve the historical limitation with independent review and explicit owner risk disposition; or (B) **only if separately authorized**, run a *fresh* W2C synthetic-only test against positively identified isolated/disposable V9, capture contemporary sanitized output and postcheck, and distinguish the new run/date from the historical one. Since synthetic W2C performs temporary database writes prior to ROLLBACK, **Option B is NOT authorized by this review**. No new Flyway migration or original V8 writes under either option.

### EX-04 — PR #30 accidental merge governance

**Current state: OPEN / ACCIDENT ACKNOWLEDGED.** Owner stated PR #30 was merged by mistake. GitHub PR #30 merge commit `35c583e05f3199c66f830ecabc4a38b16004f98b`; no intentional separate owner merge authorization before merge was recorded. PR #32 remains draft/unmerged and now contains a proposed manual merge-approval template. Owner screenshot warns that current GitHub private organization plan does not enforce the `Protect-main` ruleset.

**Evidence disposition:** No automatic rollback; merged code provisionally retained, **not permanently signed off**. Before any further merge, owner must decide retain versus separately reviewed correction, and document effective permissions/review protections; related prior incident PR #27 remains in issue #28.

### EX-05 — Real-patient security/clinical release gate

**Current state: OPEN FOR ANY CUSTOMER OR REAL PATIENT RELEASE.** Known risks include SQL context/GUC spoofing after patient credential compromise, overly broad authentication role, unfinished MFA/abuse protections, contact encryption/retention and independent security/clinical testing. No real-patient acceptance performed.

**Evidence disposition:** Never waive EX-05 based solely on synthetic schema migration acceptance. These gaps may be tracked separately for future releases; real-patient/customer use remains prohibited. A hypothetical future synthetic-only W3 GO would not approve any real-patient use.

## 4. Risk posture and future sequencing

**Current recommendation: RETAIN W3-E NO-GO/HOLD.** No new original migration or disposable synthetic tests needed merely to finish H5 documentation.

Recommended sequencing *subject to separate owner approvals*:
1. Preserve known historical gaps and existing private evidence; do not repeatedly rescan the same five locations.
2. Seek independent security/evidence assessment focused on the specific scope of **synthetic-only local V8→V9** if a future W3 GO is contemplated.
3. Resolve PR #30 retain/correct governance with explicit owner signoff; assess enforced controls before any future merge.
4. If independent reviewer judges W2C scenario evidence necessary, draft a **separately authorizable** disposable-only test plan with source/target isolation, rollback and no-original-DB checks; wait for owner permission before running it.
5. Immediately before any *new* W3 GO decision, reverify original V8, dataset synthetic-only identity, backup SHA, Git source identity and reviewed V9 script read-only. A new W3 GO requires **explicit owner approval** and does not follow automatically from a new test PASS.
6. Maintain separate customer/real-patient security and clinical acceptance gates.

## 5. Reviewer conclusion and non-authorizations

- **H5 formal evidence-exception review:** `COMPLETE_WITH_OPEN_EXCEPTIONS`.
- **EX-01:** `PARTIALLY_MITIGATED_OPEN`.
- **EX-02:** `RETROSPECTIVE_VERIFICATION_ONLY_OPEN`.
- **EX-03:** `HISTORIC_W2C_LOG_NOT_LOCATED_OPEN`.
- **EX-04:** `ACCIDENT_ACKNOWLEDGED_RETENTION_PENDING`.
- **EX-05:** `CUSTOMER_SECURITY_NOT_ACCEPTED`.
- **W3-D owner residual-risk acceptance:** `NOT_GRANTED`.
- **W3-E owner decision:** `NO_GO_HOLD_APPROVED_2026-11-06`.
- **W4 original Apply:** `BLOCKED`.
- **PR #32 merge:** `NOT_AUTHORIZED`.
- **Customer/real-patient release:** `NOT_AUTHORIZED`.

**No raw private archive, passwords, patient records, source transcript contents or local filesystem secrets were uploaded. No original DB accessed, Flyway invoked, synthetic SQL fixture run, Git PR merged or reverted, or new authorization granted by producing this document.**
