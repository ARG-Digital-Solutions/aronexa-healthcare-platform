# P01-T07-A — Owner closure approval record (2026-11-06 IST)

**Decision: OWNER_APPROVED — TASK CLOSED FOR INTERNAL_FOUNDATION ONLY.**

## Explicit owner instruction

The owner gave the following explicit direction in this conversation on 2026-11-06 IST:

> I explicitly approve formal P01-T07-A closure for INTERNAL_FOUNDATION only, acknowledging the documented limitations. Record owner closure approval in GitHub PR #27. Do not merge PR #27 or start P01-T07-B without separate authorization.

This is **task-level closure approval**, not approval to merge this pull request or advance development.

## Scope accepted

- P01-T07-A: shared patient identity / tenant-safe registration **schema foundation**, Flyway V8.
- Evidence and safeguards recorded in [closure review](P01_T07A_CLOSURE_REVIEW_2026-11-06.md) and [curated evidence](evidence/P01_T07A_20261106/EVIDENCE.md).
- Technical verification status (owner-supplied): `READ_ONLY_V8_VERIFICATION_PASSED`, including preserved preexisting counts, six new patient tables, FORCE RLS, absent runtime patient grants, Maven 71/71 tests and passing npm frontend build.
- Original pre-V8 backup retained privately and original checksum verified by owner.
- Final acceptance PowerShell script archived in draft PR #27: `scripts/acceptance/p01-t07a/Invoke-AroNexa-V8-Acceptance.ps1`. Owner's local SHA-256 matched recorded SHA `2BCA93D6A83ACAB8F4071541E51B3990F277B103FECF5A5F47B042B49AEEB933`.
- Private ZIP archive owner-reported SHA-256 `B593192A6BFEBBCD84CF243E47F48B22C90095FBEDE9CCEC5B5AA14E3DDFCE5E`: 8 archived evidence files + 2 metadata entries; zero missing; original database backup kept separately outside ZIP.

## Acknowledged limitations (not waived)

1. Archived acceptance script retains the historical **Apply** manifest property defect (`$m.applied_at` on a deserialized object without the property). Original V8 migration already succeeded; Apply must **not** be rerun. Future repair requires separate authorization.
2. Mockito's inline-mock-maker self-attachment warning remains a future JDK compatibility concern; the scoped PowerShell warning fix is not a Mockito Java-agent update.
3. npm `allowScripts` install-script warning remains a dependency-policy review item; no broad install-script permission has been granted.
4. Wider foundation security and authorization gaps, privacy/clinical review and real-patient onboarding are not resolved. This task provides a schema scaffold, **not** a patient registration API, customer-ready feature, approved clinical service, production deployment or release.
5. The source evidence archive and backup are held privately. Their original bytes and contents were not independently accessed or audited by the assistant; stated PASS results derive from owner-supplied Windows outputs.
6. No independent Windows execution of the Git-archived acceptance script was carried out in this record.

## State and separation of approvals

| Decision / activity | State |
|---|---|
| P01-T07-A internal-foundation technical verification | PASS (owner-supplied evidence) |
| P01-T07-A **owner task closure** | **APPROVED on 2026-11-06 IST** |
| PR #27 merge | **NOT AUTHORIZED**; keep open, draft and unmerged |
| Phase 01 overall closure | NOT AUTHORIZED |
| P01-T07-B execution | **NOT AUTHORIZED** |
| Deployments, customer/clinical use, real patient data | NOT AUTHORIZED |
| Database migrations / Apply rerun / schema modifications | NOT AUTHORIZED |

This approval is recorded on the **PR #27 branch only**; its existence does not imply that the closure records have been merged into `main`. Do not auto-merge this PR or trigger any further work.
