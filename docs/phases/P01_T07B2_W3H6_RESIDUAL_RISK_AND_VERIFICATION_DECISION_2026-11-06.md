# P01-T07-B2-MIG — W3-H6 Residual Evidence Risk and Verification Decision Review

**Review date:** 2026-11-06 IST  
**Scope:** Original Windows PostgreSQL 17 `aronexa_dev` synthetic-only development database, currently owner-reported Flyway V8; reviewed proposed V9 on isolated disposable PostgreSQL 17.  
**Evidence basis:** W3-D/E [formal review](P01_T07B2_W3D_GOVERNANCE_EVIDENCE_EXCEPTION_REVIEW_2026-11-06.md), [W3-E owner NO-GO/HOLD](P01_T07B2_W3E_OWNER_DECISION_RECORD_2026-11-06.md), [W3-H5 history assessment](P01_T07B2_W3H5_HISTORICAL_EVIDENCE_EXCEPTION_REVIEW_2026-11-06.md), [issue #33](https://github.com/ARG-Digital-Solutions/aronexa-healthcare-platform/issues/33), and the [binding owner decision in #31](https://github.com/ARG-Digital-Solutions/aronexa-healthcare-platform/issues/31#issuecomment-6690270572).  
**Review method:** GitHub source/issue evidence and operator-reported local commands. **No local private files or original Windows database independently inspected**. This is reviewer risk analysis only, not owner's residual-risk approval or owner GO.

## 1. Decision finding — separate what is acceptable from what still needs evidence

**W3-H6_REVIEW:** `COMPLETE_WITH_ACTION_PLAN`  
**Recommendation:** **RETAIN_OWNER_NO_GO_HOLD** until mandatory future-GO gates are satisfied.

The absence of contemporaneous historical transcripts cannot be retroactively cured. For a **synthetic-only** original V8→V9 migration, it may be reasonable for the owner to acknowledge the lost historical transcripts **after** independent inspection of current controls and the necessary new safety evidence. That does not mean every historical gap is automatically waived.

**No owner risk acceptance, no new execution authority and no technical migration certification result from this review.**

## 2. Risk-by-exception classification

| Exception | Risk to the proposed limited migration | Existing support | W3-H6 decision for future-GO review |
|---|---|---|---|
| EX-01 — contemporaneous backup transfer/restore provenance missing | **HIGH**: untested restore could compromise recovery | W1 file **92,567 bytes**, operator-reported SHA-256 `153842CCC811A64729F2DBA0743515A6C24E5A20AFB1D054AF054FECB36C3CAB`; W3-H2 host hash/size PASS; W3-H3 `pg_restore --list` reports 25 TABLE + 25 TABLE DATA entries; W3-B 25-table CSV normalized equality; W2A original restored V8 PASS was operator-reported | **Not sufficient on archive TOC alone.** Require independently reviewed **existing** restored-V8 attestations if available; if insufficient, propose a **separately authorized new disposable restore validation with capture**, not an original DB restore. At later owner decision time independently reverify original V8/source snapshot alignment, W1 hash/current backup freshness and recovery readiness; if original changed, require fresh authorized backup. Mark the past source/host hash event as unavailable, never as regenerated. |
| EX-02 — contemporaneous W2B V8→V9 migrate/validate log not located | **MODERATE**: missing historic process audit, but post-migration state can be checked | W3-C read-only PASS retained SHA `134A13AF651151391DF86F138C772ECF079FFDDE785A9679D721A7DDB3841A01`; W3-H1 all ten PASS markers including original V8, disposable V9, V1–V8 checksum preservation, +1 module/+6 permissions/+1 Flyway history/+1 audit, RLS/grants | **Potentially acceptable as a documented historical limitation**, but only after independent reviewer verifies sanitized W3-C output/implementation, exact V9 Git blob `3a1e2b717576ccd84f0de058bb0a83786c517d32`, current isolated V9 integrity and source identity; owner must explicitly disposition residual past-execution uncertainty. Do **not** require repeating Flyway migrate merely for a replacement historical log. |
| EX-03 — contemporaneous W2C negative scenario/ROLLBACK transcript not located | **HIGH** for the RLS/tenant-isolation boundary | Operator-reported W2C PASS and zero exit; repository W2C SQL/wrapper requires 15 named scenario PASS notices, ROLLBACK, transaction-context reset, and W2B postcheck | **Prefer a newly dated, separately authorized disposable-only W2C re-attestation with private sanitized 15-marker and rollback/postcheck evidence**, rather than claiming historic markers were independently seen. This **is not authorized** under H6; synthetic SQL creates temporary data within BEGIN/ROLLBACK, so requires explicit owner go-ahead and strict original/disposable identity checks. An independent reviewer can instead justify accepting historic gaps for *synthetic-only* scope with owner explicit risk acceptance, but H6 does **not** endorse waiver without such review. |
| EX-04 — PR #30 accidentally merged, branch protections not effective | **HIGH governance/process risk** to future changes and unapproved migration exposure | Owner acknowledged error; PR #30 merge `35c583e05f3199c66f830ecabc4a38b16004f98b`; PR #32 draft/unmerged; `.github/PULL_REQUEST_TEMPLATE.md` proposed but not on main. GitHub screenshot indicates private org ruleset not enforced on current plan | **Owner must explicitly choose RETAIN-with-controls vs separate reviewed correction** and record decision in #31/#33. Document that manual template/checklist is not a GitHub enforcement boundary; verify permissions/rulesets; avoid auto-revert. Do not merge PR #32 as a shortcut. Ensure no original V8 application startup with auto-Flyway from merged V9 code. |
| EX-05 — production/patient security not accepted | **CRITICAL release blocker**; distinct from synthetic-only schema migration | Documented GUC spoofing under DB credential compromise, broad auth role, MFA/abuse gaps, plaintext contact/retention, incomplete clinical review | **Remain open and mandatory before any real-patient/customer use**, but is **not automatically a blocker to an independently reviewed, synthetic-only local V9 schema migration**. Explicitly keep restrictions in any future W3 GO wording. |

**Risk labels** are review judgments based on probability/impact of proceeding without necessary evidence; they are **not** a quantitative security test.

## 3. Minimal additional proof before a new W3 GO can be recommended

### P0 — Required for the future W3 GO review

1. **Scope and recovery attestation (EX-01).** Independently review W1 SHA, 25-table baseline, evidence of a successful restored-original *disposable* rehearsal/restore, and whether the existing verified W1 backup still represents the current original V8 source. If the original W2A restore receipt is unavailable and its reported result is judged insufficient, obtain **separately authorized** fresh disposable restore and a current private evidence log. **W3-H6 does not authorize such a restore.**
2. **Direct scrutiny of isolated V9 verification (EX-02).** Reviewer examines the sanitized, retained W3-C report and script against exact required checks and V9 source digest. No additional Flyway migration is required if those checks sufficiently attest the state.
3. **Negative RLS/rollback execution evidence (EX-03).** Preferred: a new independently reviewed **disposable-only** W2C run, explicitly authorized by the owner, with 15 scenario markers, BEGIN/ROLLBACK, next-transaction reset, denied privileges and post-rollback read-only V9 checks. No original DB modifications; no test rerun without authorization. Alternative: an independent, documented exception analysis concluding retrospective test evidence is adequate for **synthetic-only** scope, followed by explicit owner residual-risk acceptance. **Neither path is yet approved.**
4. **Governance disposition (EX-04).** Owner records retain-vs-correct PR #30 disposition, manual/non-enforced GitHub protection limits, operator merge controls and the status of PR #27 issue #28. No PR #32 merge required.
5. **Fresh final read-only decision preflight.** Confirm exact original identity (Docker project/volume/database), synthetic-only dataset, original Flyway V8, private backup SHA and recovery plan, exact reviewed V9 blob and role matrix, disposable V9 status at decision time, and GitHub PR/current branch states. **Read-only only**, under separately reviewed runbook; not the original W4 Apply.
6. **Independent assessment and owner signoff.** Reviewer states the limited-scope evidence sufficiency and each accepted residual exception; then owner issues a **new explicit W3 GO** referencing issue #31 and decision evidence. W3 GO would authorize only the clearly bounded synthetic original development migration, and W4 would still need its separately reviewed runbook/controlled execution authorization, without automatic command execution.

### P1 — Mandatory for future patient/customer release, not this synthetic-only W3 GO

- Separate security and clinical assessment of authorization/GUC spoofing, credential isolation, role privileges, MFA/abuse, encryption/retention, patient consent, feature and quality gates (EX-05). **No real patient or customer deployment allowed under H6, W3 or a later synthetic-only W4 GO.**

## 4. Proposed decision choices for a later owner authorization

| Choice | What it means | H6 recommendation |
|---|---|---|
| **A — Conservative / preferred** | Keep NO-GO now. Prepare a separate **read-only readiness & evidence review**, then seek owner permission for **only** targeted, isolated tests judged essential (W2C and possibly disposable restore); capture newly dated logs, retain old missing-transcript exceptions. | **RECOMMENDED** |
| **B — Accept historical evidence limitations for synthetic-only scope** | After independent review, owner may explicitly acknowledge historical gaps and accept sufficient compensating evidence **without** re-running tests; no fabricated historical evidence. | Conditionally possible, **not yet approved** |
| **C — W3 GO now** | Directly supersede the NO-GO using only current owner-reported evidence, without independent source/restore/security execution review and PR #30 governance disposition. | **NOT RECOMMENDED / NOT AUTHORIZED** |

**Immediate next step:** W3-H7 **planning/authorization review only** (targeted disposable W2C and, if necessary, original-restore evidence replacement), plus independent EX-04 PR #30 retain/correct decision. No W2C execution or V8 original operations until separate explicit owner approval.

## 5. Execution and governance safeguards

- Preserve original V8 database, W1 private backup, W2 isolated V9, W2D ZIP and W3-C report; no cleanup, overwrites, restore into original or database role changes.
- Existing code PR #30 was merged to main, but original V8 is not known to have migrated merely because of the merge; **do not start V9 application with Flyway auto-migrate against original V8**.
- No Flyway migrate/repair/clean, no W2C synthetic SQL rerun, no Docker create/remove, no persistent DB writes, no PR merges or reverts in W3-H6.
- H6 completion is not owner risk acceptance, owner GO, PR #32 merge approval, W4 approval or patient/customer release authority.
- If owner later authorizes a targeted test, generate a *new contemporaneous* sanitized report with date+time and SHA-256 while preserving original evidence. New logs must not be described as the unavailable historical logs.

**W3_H6_REVIEW:** `COMPLETE_WITH_ACTION_PLAN`  
**W3_H6_OWNER_RESIDUAL_RISK_ACCEPTANCE:** `NOT_GRANTED`  
**W3_H6_W2C_RERUN_AUTHORIZED:** `NO`  
**W3_E_OWNER_DECISION:** `NO_GO_HOLD_APPROVED_2026-11-06`  
**W3_OWNER_GO:** `NOT_GRANTED`  
**W4_ORIGINAL_APPLY:** `BLOCKED`  
**PR32_MERGE:** `HOLD`  
**PATIENT_CUSTOMER_RELEASE:** `BLOCKED`
