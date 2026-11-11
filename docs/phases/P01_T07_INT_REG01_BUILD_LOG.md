# P01-T07-INT-REG-01 — Build and evidence log

**As-of:** 2026-11-09  
**Stage:** DRAFT IMPLEMENTATION; acceptance execution unverified; release NOT_READY.

## Source inventory
- Repository: ARG-Digital-Solutions/aronexa-healthcare-platform (private)
- Baseline backend PR #38 `cfdb03c631c2ed6df687b71d68fcc890a1eb9cc4` — GitHub SHA independently inspected.
- Read-only frontend PR #61 `52809efbc077cd6abb6860d870cc487cc8eddc42` — GitHub SHA independently inspected.
- Created branch `codex/p01-t07-int-reg01-contract-20261109` from exact backend SHA.
- Four allowed files only: this log, contract/gaps document, test JSON contract fixture, Java test class.

## Source QA update (2026-11-09)
- Static compatibility review found Jackson 2 `ObjectMapper` imports in the first draft test despite Spring Boot 4 defaulting to Jackson 3. Corrected the **test file only** to `tools.jackson.databind.json.JsonMapper` and `tools.jackson.databind.JsonNode`. This is a source-level correction, **not** a confirmed Maven compile.
- Test-only adapter now translates frozen UI fields itself and compares mapped request JSON to checked-in expected backend contract. Nonempty phone rejects before request construction, unlike a fixture-only declaration.
- Retrieved the versioned JSON fixture from GitHub and parsed it successfully; checked four unique fixture IDs, UNKNOWN/null and FULL/EXACT mappings, phone-block fixture, Jackson 3 imports, and actual adapter code. **All those static checks passed.**
- GitHub workflow-run lookup on intermediate commit `0120927a403e4bbfcedc5b8f549fe83eb7a2f9fd` returned **zero pull-request-triggered runs** and **zero commit statuses**. This is not proof of zero dispatch runs, but no CI evidence has been obtained.
- The existing workflow `.github/workflows/pr-quality.yml` on `main` exposes `workflow_dispatch`. Manually dispatch on this branch with the GitHub CLI; do not retarget PR #64 to main.

## Implemented (source-level; not test PASS)
- Explicit UI-to-DTO mapping and unsupported-field policy.
- Versioned synthetic fixture contract and error-code table.
- Separate PostgreSQL Testcontainers-backed MockMvc tests for 201, 200, 409, 400, 401 and 403, stable idempotency, duplicate no-merge and phone block.
- Java test reuses established C2 bootstrap, isolated test-only migration, ephemeral proof key and synthetic identities.

## Execution evidence (do not infer PASS)
| Gate | Status | Evidence |
|---|---|---|
| Approved baseline/ref inspection | VERIFIED | GitHub PR #38 / PR #61 exact head SHAs |
| Isolated branch creation | VERIFIED | GitHub branch API |
| Scope-limited source changes | VERIFIED ON INTERMEDIATE HEAD | GitHub compare: four allowed files, no other paths; recheck final head |
| Java compilation | NOT RUN | No Docker-enabled GitHub Actions execution or local Maven run for this head |
| PostgreSQL Testcontainers HTTP tests | NOT RUN | Docker unavailable here |
| Full Maven regression | NOT RUN | Maven/Docker unavailable here |
| Frontend reproducible build | NOT RUN | The workflow builds the backend branch frontend, **not** the frozen PR #61 SHA; separately verify frozen frontend baseline |
| SQL grants and FORCE RLS | NOT RERUN | Existing C2 tests require fresh environment |
| Profile gating, rate limiting and payload bound | NOT RERUN | Existing C2 tests require fresh environment |
| SAST / dependency / secret checks | NOT RERUN | CI evidence required |
| JSON syntax and contract static checks | PASS (source-level only) | Four fixture IDs and field-mapping assertions read from GitHub; not Java runtime execution |
| Independent PR review | PENDING | Never self-approve |

## Minimal-intervention remote CI dispatch
With GitHub CLI authenticated on the operator workstation:
```powershell
gh workflow run pr-quality.yml --repo ARG-Digital-Solutions/aronexa-healthcare-platform --ref codex/p01-t07-int-reg01-contract-20261109
gh run list --repo ARG-Digital-Solutions/aronexa-healthcare-platform --workflow pr-quality.yml --branch codex/p01-t07-int-reg01-contract-20261109 --event workflow_dispatch --limit 5
```
Verify workflow run head SHA matches the **final** PR #64 head and inspect all jobs. Record actual counts and URLs in a new evidence update; do not mistake workflow dispatch success for test success.

## Mandatory next QA step
Use `mvn -B -f backend/pom.xml verify` on this exact branch with Docker-enabled fresh Testcontainers; run frontend `npm ci --no-audit --no-fund && npm run build`; check scope, logs, sensitive data redaction and CI security gates. Do not claim success if any check is skipped, timed out or inconclusive. Update draft PR with actual SHA, job URLs, test counts, failures, and reviewer disposition. The regular `pr-quality.yml` workflow declares pull_request base `main` only: a draft PR based on the protected C2 feature branch may **not** trigger it. Do not retarget to main solely to force CI; use explicitly approved isolated test execution or a separate authorization.

## Release hold
No merge, original database, persistent migration, live frontend activation, production config, deployment or customer release. Any blocked test requiring out-of-scope changes is to be documented, not silently repaired.
