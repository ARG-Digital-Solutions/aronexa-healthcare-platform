# P01-T07-B2 — W3-D Governance and Evidence-Gap Review

**Review date:** 2026-11-06 IST  
**Task:** P01-T07-B2-MIG, issue #31, preparation of a formal W3 owner decision  
**Classification:** INTERNAL_FOUNDATION / SYNTHETIC DEVELOPMENT ONLY  
**Review outcome:** **PARTIAL — evidence inventory classified; governance and approval exceptions remain OPEN**  
**Owner W3 GO:** NOT GRANTED  
**Original persistent V8→V9 Apply (W4):** BLOCKED  
**PR #32:** DRAFT / UNMERGED; no merge authorization

## A. Scope, source classification and exclusions

This report reviews repository/PR activity and the operator's already-supplied local acceptance results. It **does not** access the user's Windows PostgreSQL volumes, inspect the private archive bytes, review missing original transcripts, issue independent penetration-test certification, or authorize migrations, merges or customer use.

**Directly checked in GitHub:** issue #31 and linked issue #28, PR #27/#30/#32 metadata, PR #30 comment and review submissions, merge-commit metadata and filenames, PR #32 CI status, existing formal W3 review and B2 owner/security documents.

**Operator-reported and retained privately:** original V8 preflight/backup and restored-V8 rehearsal, V9 disposable migrate/validate, W2B read-only PASS, W2C isolation/rollback PASS, W2D ZIP integrity, W3-B table count normalization, W3-C read-only re-attestation and W3-D W3-C report hash.

## B. Governance deviation — PR #30

| Control | Verified observation |
|---|---|
| GitHub PR #30 | MERGED at 2026-11-06T08:36:25Z |
| Merge commit | `35c583e05f3199c66f830ecabc4a38b16004f98b` |
| GitHub commit author | `argsolutions2017` |
| GitHub commit committer | `web-flow` |
| Merge message | `Merge pull request #30 from ARG-Digital-Solutions/feature/p01-t07b-patient-onboarding` |
| PR review submissions | 0 returned by GitHub review API |
| PR #30 instruction/owner-acceptance record | Explicit **DRAFT / DO NOT MERGE** without separate authorization |
| Separate owner approval to merge PR #30 | **Not found in reviewed records** |
| Corrective action | Approval provenance unresolved; no automatic revert |

**Interpretation:** Commit author/committer metadata identifies the account associated with the merge commit and GitHub's web-flow committer; it does **not conclusively identify the human/API actor who initiated the merge, whether an approved delegation existed or the branch-rule bypass path**. GitHub activity/organization audit events and repo ruleset/branch protection settings were not accessible through the available connector read operations. The discrepancy must be reconciled by an owner-accessible GitHub Activity/audit and permissions review. Do **not** label it malicious or retroactively authorized.

The merged PR includes application runtime, V9 migration, tests, workflow, documentation and acceptance scripts: **code merge to `main` is not evidence that the original persistent database migrated**, and conversely a future launch of V9 code against the original V8 connection could trigger Flyway auto-migration. Keep original Windows startup blocked until explicit W4 GO.

**Related history:** PR #27 was previously merged despite a recorded hold; the discrepancy is tracked in open issue #28. Address recurring review-state/merge controls before future merges. Do not revert PR #27 or #30 without a separate risk assessment and owner authorization. Any corrections to stale merged documentation should be additive, auditable and separately reviewed.

**Current PR #32:** OPEN / DRAFT / UNMERGED at review; its PR Quality Gates on the prior formal-W3-review commit `78c77099d6aab5875025962b6c42732e270c8dbd` completed SUCCESS. Successful CI is not owner merge approval.

### Actions still required for governance closure

- [ ] Owner examines GitHub PR #30 Activity/timeline and any organization audit event for `merged`, actor, integration, authorization and timestamp; reconciles with `argsolutions2017` / `web-flow` commit metadata.
- [ ] Owner reviews effective branch rulesets, required reviews, bypass allowances, permissions/tokens, and whether draft PR merge was permitted after an unrecorded ready-for-review transition.
- [ ] Owner decides whether to retain the already-merged code with documented compensating approval, or commission a separately reviewed non-destructive correction. **No automatic revert.**
- [ ] Owner documents the decision in issue #31 and updates stale PR #30/B2 acceptance records. Link issue #28 remediation for PR #27.
- [ ] Require explicit owner merge authorization and passing required checks/reviews for any future PR #32 merge.

## C. Evidence inventory and retention

| Gate | Current evidence | Status and provenance limitation |
|---|---|---|
| W0 — original V8 preflight | Original operator-reported `READ_ONLY_V8_PREFLIGHT_PASSED` | PASS reported; original saved transcript **not located** in reviewed 19-file inventory |
| W1 — backup | 92,567-byte custom-format backup SHA-256 `153842CCC811A64729F2DBA0743515A6C24E5A20AFB1D054AF054FECB36C3CAB`; original `pg_restore --list` and source/host match previously reported PASS | Hash/restore assertions owner-reported; original source-and-host command transcript **not located** |
| W1 baseline | EARLY_W1 / LATER_W1 / W2D each **25** unique tables, semantic counts match | W3-B normalized reconciliation **PASS** (owner report), although raw CSV bytes differ in size |
| W2A — restored V8 | Original Flyway V8, V1–V8 checksums and all 25 counts reported reconciled, disposable roles bootstrap | PASS owner-reported; original restore/ACL transcript **not located** |
| W2B — migrated disposable V9 | Migration/validate reported PASS; dedicated read-only verifier exit 0; required V9 seed, module audit, RLS/grants asserted | PASS owner-reported; contemporaneous migrate/validate transcript **not located**. W3-C later read-only check is independent in time, **not a recreation of migration event** |
| W2C — synthetic authorization/rollback | `W2C_SYNTHETIC_ISOLATION_AND_ROLLBACK_PASS`, exit 0 | PASS owner-reported; original per-scenario/rollback transcript **not located**. Cannot infer each specific negative check from private log not reviewed |
| W2D — private summary ZIP | ZIP SHA-256 `9BEF04CBA2FC7E5DAF790E34521538558713B8A0B164979FE52ED7E011C9BE97`, 3 allowlisted entries and 2 SHA-manifest files verified | ZIP outer + inner integrity PASS owner-reported; content is summary, 25-table CSV, SHA manifest only — **not** original W0–W2C execution logs |
| W3-C — retrospective read-only V8/V9 checker | `W3C_READ_ONLY_V9_INTEGRITY_PASS`; retained private report SHA-256 `134A13AF651151391DF86F138C772ECF079FFDDE785A9679D721A7DDB3841A01`; W3-D report rehash PASS | PASS owner-reported, retention/hash established against owner-provided value; content not independently inspected |

The extended 19-file inventory yielded 14 files in earlier P01-T07-A acceptance directories, two W1 baseline CSVs, three W2D sanitized evidence files. These **do not include** original saved W2A/W2B/W2C execution transcripts. The original PowerShell session may have printed those checks without saving them. Absence from this inventory does not prove they were never retained in any other folder. No fabricated log or backdated report may be accepted.

### Evidence exceptions (must remain explicit)

- **EX-01 — Original W0/W1/W2A execution receipts not independently available.** Compensating controls: retained backup re-hash, matching 25-table baseline and successful restored-original acceptance; source-vs-host original backup hash *event* still requires separately attested provenance.
- **EX-02 — Contemporaneous W2B migrate/validate transcript not independently available.** Compensating controls: restored-original disposable V9 currently validated by reported W2B and later W3-C read-only scripts, Flyway V1–V8 checksums and exact expected V9 row changes; cannot retrospectively recreate the original command timeline.
- **EX-03 — Contemporaneous W2C per-scenario transcript not independently available.** Owner-reported exit 0 and final marker were provided; inspect any independently retained session log if available. If no transcript exists, note uncertainty and seek explicit owner decision on whether a **separately authorized** fresh synthetic-only rollback scenario run with private sanitized capture is needed; never rerun Flyway.
- **EX-04 — PR #30 governance deviation unresolved.** No prior separate merge approval found; account/committer metadata is suggestive but not conclusive human/action attribution. Owner audit review and explicit reconciliation required.
- **EX-05 — No independent adversarial security assessment or customer-release gates.** Accepted limited INTERNAL_FOUNDATION implementation and synthetic rehearsal must not be presented as secure real-patient release.

## D. Proposed evidence disposition and remaining closure gates

**Disposition today:** Evidence inventory classification is COMPLETE; technical results are OWNER-REPORTED PASS with W3-B and W3-C compensating read-only checks; **full evidence-provenance and governance closure is NOT COMPLETE**.

To complete W3-D without fabricating historic evidence:
1. Request the owner locate original reports only if present outside the earlier filename-filtered inventory. Otherwise mark the historical raw transcripts **UNAVAILABLE** and explicitly accept/reject the limited retrospective evidence approach; no reconstructed past report may claim contemporaneous origin.
2. Review locally retained W3-C report *content* in a sanitized context, the verified W1 backup digest/restore metadata, and original W2C check set as necessary for the intended synthetic-only W4 operation.
3. Owner reconciles PR #30 (and issue #28 PR #27), documents merge authorizing decision/exception and introduces controls for future merges.
4. Verify original PostgreSQL still V8 using read-only checks at the actual W3 decision time.
5. Independent reviewer issues an explicit scoped assessment; owner then records **a separate W3 GO or NO-GO** for W4 limited to original synthetic Windows development DB. No GO by silence or by passing CI.

## E. Technical / operational boundaries

Existing threats from the B2 security review remain: direct patient runtime credential compromise can spoof transaction-context GUCs; broader authentication role privileges; incomplete MFA/abuse, patient-contact encryption/retention and clinical security review. These prevent customer or real-patient authorization even if a future synthetic W4 migration is separately approved.

**NO changes made to original Windows PostgreSQL. NO W4 Flyway migrate, role provisioning, Docker teardown/restore, cleanup, PR #32 merge, launch, patient/customer data or security signoff.**

**W3_D_EVIDENCE_CLASSIFICATION:** COMPLETE  
**W3_D_GOVERNANCE_RECONCILIATION:** PENDING OWNER AUDIT  
**W3_D_HISTORIC_TRANSCRIPTS:** NOT LOCATED  
**W3_OWNER_GO:** NOT GRANTED  
**W4_ORIGINAL_MIGRATION:** BLOCKED  
**PR32_MERGE:** HOLD  
