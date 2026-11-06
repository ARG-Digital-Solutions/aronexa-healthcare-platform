# AroNexa pull request — engineering review and owner merge decision

> **Approval policy:** A passing CI run, an accepted internal-foundation task, a completed development phase, or a draft PR does **not** authorize a merge. **Do not merge until the project owner explicitly approves this specific PR in a new comment after reviewing the final commit and scope.**
>
> **Repository-plan limitation:** On a GitHub plan where private organization rulesets are not enforced, this checklist is a **manual procedure, not a technical merge lock**. Verify any applicable ruleset is effectively enforced before relying on GitHub to block an incorrect merge.

## Scope and readiness

- [ ] Task / issue and phase identified: `#...`
- [ ] Change purpose, acceptance criteria, and modified components explained
- [ ] Scope is limited to the separately authorized work item; no unauthorized next-phase implementation
- [ ] Final head commit SHA recorded below and source diff reviewed
- [ ] Evidence is accurately classified as **repository-verified**, **owner-reported**, or **not inspected**; missing historical logs are not represented as contemporary evidence

## Technical verification

- [ ] Documentation validation PASS
- [ ] PR scope and dangerous-file checks PASS
- [ ] Tracked secret patterns PASS
- [ ] Application CI (when scaffold exists) PASS
- [ ] Required workflow/check results are from the latest PR head and current `main`
- [ ] No secrets, patient identifiers, passwords, private dumps, or raw sensitive logs committed
- [ ] Tests and migrations reviewed; actual run environment, outcome and evidence limitations stated
- [ ] An independent reviewer/owner has inspected high-impact database, security and infrastructure changes where applicable

## Database, security and release controls

- [ ] Database migration impact documented: **NONE / DISPOSABLE ONLY / ORIGINAL DEV / CUSTOMER** (select one and explain)
- [ ] If original-database Apply is proposed, a **separate explicit owner GO** exists with verified backup, rehearsal and current preflight
- [ ] No automatic Flyway run or application startup is allowed against an original V8 database while the W3/W4 gate is HOLD
- [ ] Any role/permission/RLS/tenant-isolation change has been specifically reviewed
- [ ] Synthetic/internal-foundation acceptance is not represented as approval for real patient data, customer release, or clinical/security certification
- [ ] Known blockers, exception register and rollback/forward-recovery approach documented

## Governance and owner merge authorization

- [ ] Pull request remains **Draft** during review; conversion to Ready is not by itself merge authorization
- [ ] Open review comments resolved and final scope/CI reviewed
- [ ] Owner has added a **separate affirmative comment** on **this PR**, referring to its final head SHA, in this form:
  `I explicitly authorize merging PR #<number> at head <40-character SHA> into main. This is merge authorization only; it does not authorize database migration, deployment or customer release.`
- [ ] For database Apply or customer release, **additional, separately recorded owner authorization** is present
- [ ] Merge actor verifies that the approved SHA still matches the PR head immediately before merging
- [ ] If any requirement is unresolved or the final head changed since approval, **STOP / DO NOT MERGE**

**PR head SHA for review:** `<sha>`  
**Owner approval comment link:** `<link>`  
**Original-database migration authorization:** `NONE unless separately supplied`  
**Customer/real-patient release authorization:** `NONE unless separately supplied`

> **Current P01-T07-B2 gate (as of 2026-11-06):** W3 owner GO NOT GRANTED; W4 persistent Windows V8→V9 original Apply BLOCKED; draft PR #32 is NOT authorized to merge. PR #30 was merged accidentally and must be documented as such, not treated as an implicit release or migration authorization.
