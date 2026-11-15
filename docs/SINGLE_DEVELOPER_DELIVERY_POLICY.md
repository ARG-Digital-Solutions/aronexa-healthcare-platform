# Single-developer delivery policy — GOV_SINGLE_DEV_01

**Decision:** owner-authorized guideline amendment for future internal engineering and QA. The owner stated that arranging a separate qualified independent reviewer and operator is not feasible for this single-developer project and requested an updated guideline. This records that instruction; it is not a test result or execution acknowledgement.

**Scope:** the already-authorized Clinic Edition OPD V1 and bounded WIN-11B QA engineering. This amendment replaces blanket requirements for a second person within that internal scope. It supplements the existing single-developer milestone approval. The separate GOV_WEB_01 proposal about QA frequency/handoff scheduling remains unapproved and unchanged.

## Practical review and execution model

| Activity | Permitted responsible party | Required record |
| --- | --- | --- |
| Routine implementation and fixes | Owner and coding assistant | Authorized scope, exact revision, changes and actual verification |
| Technical review | AI review, preferably a fresh review pass, plus owner disposition when needed | `AI_REVIEW` and `OWNER_REVIEW`, with findings and unresolved risks |
| Internal scope/migration/key-design review | Owner using AI review and relevant automated evidence | Explicit owner decision for the exact proposal; no automatic deployment or persistent-database permission |
| Repeatable Windows tests | Automated execution on an appropriately isolated Windows environment | Actual Windows/JVM/database identity, counts, failures and artifact integrity |
| Checks that genuinely need an operator | The same owner may operate the isolated fictional-data lab | `OWNER_OPERATOR`, actual actions/observations and exact run/evidence binding |
| Internal acceptance | Owner, supported by the required automated evidence | `OWNER_ACCEPTANCE`; never represented as independent human signoff |

The implementation author, owner reviewer and owner operator may be the same person. A second GitHub username is not a second reviewer. An AI agent is not a human operator or an independent human assessor. Do not require an external hire or repeated routine owner confirmation merely to continue already-authorized internal work.

## Development and Windows acceptance

Continue bounded web-first implementation, defect correction and fictional automated testing under the standing approval. A separate operator is not a prerequisite for those activities. Missing Windows/operator evidence belongs to the affected Windows acceptance gate; it is not a blanket freeze on all authorized web engineering.

Automate Windows checks wherever they can prove the requirement without inventing human observations. A check that needs a real click, device, printed output, observation or explicit operator acknowledgement must be performed by the owner before it is reported as executed. Do not substitute an unattended script or a policy approval for that action. Unavailable Windows, device or manual checks remain `NOT_RUN`; they may be scheduled when the shared candidate is ready, subject to the existing handoff requirements.

Actual Windows runtime, distinct-JVM identity, persistence/restart, licensing/printing and genuine encrypted backup/restore evidence remain required wherever applicable. Unencrypted recovery never substitutes for encrypted recovery. Preserve all existing clinical, authorization, tenant/RLS and data-integrity assertions. This amendment does not delete any of the eight guarded methods or mark them passed.

## Evidence and handoffs

Product AGENTS.md rule 27 is retained verbatim. At every handoff, verify the matching current source SHA, QA revision, plan, unique request, linked run, counts, failures/skips, artifacts and actual disposition. A missing or stale Central result is not PASS. Local feedback does not replace the required Central handoff. On failure, correct defects only within authorized scope and record the real rerun.

For future adapted internal QA, a real owner receipt must bind source SHA, QA SHA, plan ID, request ID, run ID/attempt and artifact digest, and identify which observations the owner actually made. The policy change permits owner identity; it does not manufacture that receipt. Keep owner acceptance and AI findings distinct from test outcomes. Missing, failed, skipped, contradictory or unexecuted mandatory evidence keeps overall acceptance `NOT_PASS` and the affected product classification `NOT_READY`.

Preserve historical reports and receipts. Older independent-mode plans and records describe their original requirements; they are not retroactively rewritten as owner-accepted. No old PASS may be carried onto a new documentation or code revision without the applicable exact-revision evidence.

## Automation compatibility and administrator controls

This change updates guidelines only. Current WIN11P validators require a reviewer/operator different from the implementation authors and require a GitHub APPROVED review. GitHub also does not let an author approve their own PR. **Those validators are not yet adapted to this policy, and the existing workflow remains inactive.** Do not spoof a second account, forge a review event, remove validation ad hoc, or label the existing workflow ready.

The next bounded automation task must introduce an explicit owner-reviewed internal mode, test genuine owner-receipt provenance and invalid/stale/tampered rejection, and preserve strict source/path/inventory checks, credential-isolated execution/reporting, evidence digests and identity-bound cleanup. Record the policy ID and acceptance role in its evidence. Keep any separately applicable independent-assessment mode honest.

The administrator checklist must be revised for one owner before activation. Required environment approval must not depend on an unavailable second person. Where a reviewed internal mode uses the triggering owner as the approver, document that self-review prevention cannot apply to that mode and validate the replacement owner approval/receipt controls. Keep least privilege, isolated ephemeral resources and privileged reporter separation. This document changes no GitHub setting, credential, environment, runner or workflow. Earlier administrator API 403 responses remain unknown configuration state.

## Risk and release boundaries

The owner accepts the reduced separation of duties for this internal single-developer process. Fresh AI review, focused negative/concurrency tests, immutable evidence and explicit owner decisions reduce risk; they do not create independent assurance. If a concrete unresolved clinical/security risk requires expertise or a legal/contractual assessment, identify that specific risk and affected pilot/release condition. Do not impose a blanket external-review requirement on every routine increment.

Customer release still requires applicable clinical verification, privacy/security assessment, recovery rehearsal and explicit release authorization. Real-patient use, original/customer database operations, protected-settings changes, workflow activation, merge and deployment retain their own applicable authorization and evidence requirements. No automatic merge or deployment is introduced.

## Current disposition

- Personnel policy amendment: **OWNER_AUTHORIZED** for the internal scope above.
- Machine-policy implementation: **PENDING**; no executable plan, workflow or execution allowlist changed.
- Existing application evidence: product `bd3fc7b49ffb168b0d8c08f64ff10b3a7c691879`, QA `2437a9d0b7fbbd53061a749789bfd77e6fb8f458`; historical exact-revision evidence only.
- Eight guarded methods: **NOT_RUN**. Overall acceptance: **NOT_PASS**. WIN-11B: **NOT_READY**. Rule 27: preserved.
