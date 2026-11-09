# P01-T07-INT-REG-01 — Build and evidence log

**As-of:** 2026-11-09  
**Stage:** DRAFT IMPLEMENTATION; acceptance execution unverified; release NOT_READY.

## Source inventory
- Repository: ARG-Digital-Solutions/aronexa-healthcare-platform (private)
- Baseline backend PR #38 `cfdb03c631c2ed6df687b71d68fcc890a1eb9cc4` — GitHub SHA independently inspected.
- Read-only frontend PR #61 `52809efbc077cd6abb6860d870cc487cc8eddc42` — GitHub SHA independently inspected.
- Created branch `codex/p01-t07-int-reg01-contract-20261109` from exact backend SHA.
- Four allowed files only: this log, contract/gaps document, test JSON contract fixture, Java test class.

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
| Scope-limited source changes | PENDING FINAL DIFF CHECK | Verify exact changed-file list |
| Java compilation | NOT RUN | Maven CLI unavailable in this execution environment |
| PostgreSQL Testcontainers HTTP tests | NOT RUN | Docker unavailable here |
| Full Maven regression | NOT RUN | Maven/Docker unavailable here |
| Frontend reproducible build | NOT RUN | Node/npm build unavailable from repository checkout here |
| SQL grants and FORCE RLS | NOT RERUN | Existing C2 tests require fresh environment |
| Profile gating, rate limiting and payload bound | NOT RERUN | Existing C2 tests require fresh environment |
| SAST / dependency / secret checks | NOT RERUN | CI evidence required |
| Independent PR review | PENDING | Never self-approve |

## Mandatory next QA step
Use `mvn -B -f backend/pom.xml verify` on this exact branch with Docker-enabled fresh Testcontainers; run frontend `npm ci --no-audit --no-fund && npm run build`; check scope, logs, sensitive data redaction and CI security gates. Do not claim success if any check is skipped, timed out or inconclusive. Update draft PR with actual SHA, job URLs, test counts, failures, and reviewer disposition. The regular `pr-quality.yml` workflow declares pull_request base `main` only: a draft PR based on the protected C2 feature branch may **not** trigger it. Do not retarget to main solely to force CI; use explicitly approved isolated test execution or a separate authorization.

## Release hold
No merge, original database, persistent migration, live frontend activation, production config, deployment or customer release. Any blocked test requiring out-of-scope changes is to be documented, not silently repaired.
