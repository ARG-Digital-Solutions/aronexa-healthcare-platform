# AroNexa isolated local + self-hosted CI runbook (2026-11-12)

**Status:** REVIEW ONLY. **Classification:** INTERNAL_FOUNDATION / NOT_READY. No deployment or clinic release authorized.

## Audit

Application workflows `pr-quality.yml`, `dev-runtime-acceptance.yml`, `central-qa-handoff.yml` and `central-qa-lint.yml` use GitHub-hosted Linux jobs. QA Central workflows also use `ubuntu-latest`. Existing app CI includes Maven/Testcontainers with PostgreSQL, frontend TypeScript/model tests/Vite previews, documentation/scope/secret scans, and separate dependency/SAST checks. QA Central supplies synthetic localhost Playwright suites. Do not consider preview tests proof of a persistent integrated OPD application.

## Secure runner prerequisites

- **Isolated, throwaway Windows VM** or dedicated isolated host; never the development PC, original database server, or a clinic PC. No patient records, production accounts, shared personal drives, cloud signing keys, reporter/dispatch secrets or clinic network.
- Register **repository-level** Windows x64 self-hosted runner for `ARG-Digital-Solutions/aronexa-healthcare-platform` in **Settings > Actions > Runners > New self-hosted runner** following GitHub's live commands. Add custom label `aronexa-isolated-ci`. Do **not** grant it to the whole organization.
- Install Java JDK 21, Maven 3.9+, Node.js 22+, Python 3, Git, and Docker Desktop with **local Linux engine** running. Docker rights are high privilege; reimage the dedicated VM after risky runs. Prefer disposable/ephemeral registration.
- The proposed workflow `.github/workflows/local-self-hosted-ci.yml` is **manual-only**, read-only `contents`, with exact full SHA, 90-minute timeout and acknowledgement. It deliberately has **no PR/push/schedule trigger**, cross-repository dispatch, secret passing or deployment. No claim that it securely executes unreviewed source.
- Keep existing GitHub-hosted CI and branch protection settings **unchanged**. Hosted checks may remain blocked while the Actions budget is exhausted; **never disable required security checks** for convenience. This new manual workflow is not automatically a replacement for required PR checks.
- The workflow becomes available for manual dispatch after independently reviewed workflow changes reach the repository **default branch**. Before that, execute the scripts locally.

## Run application CI now, without GitHub Actions minutes

Use PowerShell on a dedicated synthetic-only Windows environment. The input is your existing clone, and the new worktree must be a distinct, previously unused directory.

```powershell
$repo = 'C:\Users\ganes\Application Development\AeroNexa 02-Nov\aronexa-healthcare-platform'
Set-Location -LiteralPath $repo
git fetch origin
$branch = 'ci/isolated-local-gates-20261112'
$sha = (git rev-parse "origin/$branch").Trim()
$worktree = Join-Path (Split-Path $repo -Parent) 'AroNexa-Isolated-CI'
git worktree add --detach $worktree $sha
if ($LASTEXITCODE -ne 0) { throw 'Cannot create clean release CI worktree' }
Set-Location -LiteralPath $worktree
& .\scripts\ci\Invoke-AroNexa-LocalCI.ps1 -RepoPath $worktree -ExpectedSha $sha
if ($LASTEXITCODE -ne 0) { throw 'Local CI failed: STOP' }
```

The script requires a clean SHA-pinned worktree, runs project documentation/scope/secret checks, checks local Docker, validates development Compose **configuration only**, runs real Maven Testcontainers tests, performs frontend build/model tests and npm high/critical audit, and verifies release-gate unit tests. Actual backend test skips or missing JUnit report fail closed. The script creates JSON evidence under the system temporary directory, outside either repository, and does **not** touch customer data or deploy.

**The branch above is a proposed CI change branch, not an approved application release baseline.** Once reviewed, apply the CI changes on top of the accepted integration source or cherry-pick their commits, then pin that new exact source SHA. Do not release a PR merely because tests pass.

## Independent QA Central

Check out the separately reviewed QA branch `ci/local-verified-qa-20261112` on an isolated QA worktree/VM. On a **clean, SHA-pinned application checkout**, run:

```powershell
$qa = 'C:\Users\ganes\Application Development\AroNexa-QA-Central'
Set-Location -LiteralPath $qa
& .\scripts\Invoke-AroNexa-LocalVerifiedQA.ps1 -AppRoot $worktree -ExpectedAppSha $sha
if ($LASTEXITCODE -ne 0) { throw 'Verified browser QA failed: STOP' }
```

Only localhost:5175 is permitted; occupied port is rejected. The script starts an isolated Vite frontend, runs `tests/verified` against Chromium with the existing Playwright config and checks nonzero XML test count with zero failures/skips, then stops the synthetic web server. It records **both** Git SHAs in `qa-ci-evidence.json`. It uses no reporter token or central QA dispatcher.

## Release evidence validation (does NOT deploy)

After actual application and browser evidence exist, prepare a privately held acceptance JSON with schema version 1, **exact app SHA**, `release_type: CUSTOMER_PILOT`, explicit `owner_approved: true`, `owner_identity`, `approval_reference`, plus a `reviews` object. Every listed check in `scripts/ci/release_gate.py` must include `status: PASS` and a real `evidence_reference`.

```powershell
python .\scripts\ci\release_gate.py `
  --app-evidence 'C:\evidence\application-ci-evidence.json' `
  --qa-evidence 'C:\evidence\qa-ci-evidence.json' `
  --acceptance 'C:\secure-evidence\customer-pilot-acceptance.json' `
  --expected-sha $sha
if ($LASTEXITCODE -ne 0) { throw 'Release gate BLOCKED' }
```

This is a **structural evidence checklist only**; it cannot authenticate review signatures or confirm independent review actually happened. It does not generate the Windows installer or grant customer deployment permission. A real offline installation, license expiry, encrypted backup/restore rehearsal, tenant/branch/role/RLS negative testing, clinician prescription review, privacy/consent assessment, rollback, customer UAT and explicit owner go/no-go decision are **still mandatory**.

## Execution/approval status

- GitHub branches and draft PRs are **review-only**; existing workflow/branch settings not changed.
- Windows local CI: **NOT RUN** in this change session.
- Full Maven/Testcontainers, npm security audit, application build, Playwright: **NOT RUN** in this change session.
- Python validator adversarial unit tests: **8 PASSED** in a separate local validation copy; Windows behavior still unverified.
- Dedicated isolated VM runner: **NOT REGISTERED**.
- Installer, recovery rehearsal and clinical approval: **NOT RUN**.
- Customer release: **BLOCKED**.
