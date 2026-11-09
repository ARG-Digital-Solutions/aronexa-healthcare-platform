# AroNexa — Central QA Handoff for Future Development PRs

**Status:** review-only automation proposal; does not supersede Master Engineering Contract V3.0 or any owner approvals.  
**Authoritative QA repository:** https://github.com/ARG-Digital-Solutions/aronexa-qa-central  
**QA setup and audit:** `docs/AUTO_QA_01_CROSS_REPO_SETUP.md` in the QA Central automation PR.

## How the development agent uses QA Central

1. Implement *only* a separately owner-authorized task in one isolated branch/worktree and create a draft PR. Do not merge.
2. Ensure QA Central has an independently reviewed `qa/plans/<PLAN_ID>.json` specifying the required test suites, exact authorized source paths, baseline, frontend reference and security gates. A new module needs its own plan and reviewed workflow; unrelated plans cannot be silently reused.
3. Add **exactly one** development PR label `qa-plan:<PLAN_ID>`. For P01-T07-INT-REG-01 only: `qa-plan:P01_T07_INT_REG_01`.
4. The trusted default-branch `.github/workflows/central-qa-handoff.yml` is triggered by PR opened/updated/labeled events. It does not checkout or execute PR source. It dispatches exact PR SHA + QA plan to QA Central and waits for a uniquely correlated commit status. No local PowerShell interaction is required after initial setup.
5. Read the latest **exact-SHA** commit status `aronexa/central-qa/<PLAN_ID>`, linked QA Central Actions child run and its JUnit/Playwright artifacts and reviewer comments. Failed, skipped, missing, timed-out, stale or mismatched results are **not PASS**.
6. A green automated result permits continuation **only on already-authorized development work**. Do not auto-merge, deploy, run original DB migrations, enable clinical APIs, release to customers or independently sign off security. Independent reviewer and owner decision remain required wherever specified.

## Required setup by repository administrator

- QA Central: review/merge central workflow + plan registry PR; configure `ARONEXA_SOURCE_READ_TOKEN` with read-only Contents on this private repo (already used for earlier manual CI) and `ARONEXA_DEV_STATUS_TOKEN` with **Commit statuses: write** on the product repo only.
- Development repo: review/merge this workflow-only PR; create the `ARONEXA_QA_DISPATCH_TOKEN` secret with **Contents: write** on **QA Central only**, never on product code.
- Turn on GitHub Actions for both repositories; allow the QA Central controller job to dispatch its own workflow via limited `GITHUB_TOKEN` `actions:write`. The code compiles in the unprivileged *child* QA workflow, not in dispatch/report jobs.
- Attach one reviewed QA plan label to each intended PR. Missing label intentionally skips dispatch and must not be mistaken for approval; enforce a process requiring a plan before eligible code proceeds.
- If branch protection/required status checks are available, require the relevant QA handoff check for eligible PRs. Some rulesets for private repositories may require a GitHub plan upgrade; no protection settings are changed by these PRs.

## Current registration checkpoint

P01-T07-INT-REG-01 product draft PR #64 has a verified test-only QA Central successful run: https://github.com/ARG-Digital-Solutions/aronexa-qa-central/actions/runs/42585442420 (157 backend tests + 54 frontend model/browser tests). It is **not** an integrated frontend-to-backend system or production release approval. PR #64 remains draft with feature scope constraints and external review requirements; do not merge it solely because tests pass.

## Security and scope

- Uses `pull_request_target` so the dispatch workflow itself is trusted from the default branch. No PR checkout; no untrusted source code execution in token-bearing jobs.
- Never send GitHub secrets to the application under test; QA Central checks out source with `persist-credentials: false` and runs tests on an isolated GitHub-hosted runner.
- Validate source repository, exact current SHA, source-owned branch, intended base branch and currently applied QA plan label before QA execution/status publication.
- The result context includes the unique run-request identifier. A green result from a different SHA or old request is not accepted.
- Report success/failure under a single consistent context; source job fails closed if the exact status is missing or indicates failure.
- Future development agents must not widen QA workflow scope, edit protected CI, or change test assertions without new review and explicit task authorization.

## Manual operator fallback

If automatic dispatch credentials are not configured or automation is under review, the existing `qa-int-reg01.yml` in QA Central may still be dispatched manually against an exact approved SHA. The original Windows/production database must never be used.

**No new integration workflow has been executed yet; all new changes remain in draft review PRs until approved.**
