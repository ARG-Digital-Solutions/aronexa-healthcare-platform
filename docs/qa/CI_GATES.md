# P00-T09 GitHub Actions PR gates
Status: implementation proposed on review branch; do not claim checks have executed until GitHub Actions reports results.

PR Quality Gates runs for PRs against main and on manual dispatch:
1. Documentation validation: required baseline files are nonempty; phase register has phases 00–14.
2. PR scope: rejects common credential/key/database artifact paths; does NOT replace human scope review.
3. Lightweight tracked-secret detection: limited signature patterns, NOT a comprehensive security audit. A specialized dependency/secret scan and supply-chain controls should be enabled before customer releases.
4. Application CI: if backend/pom.xml and frontend/package.json both exist, runs Maven verify and npm build; otherwise outputs NOT RUN. Requires a lockfile and valid Maven setup when scaffolded; add JUnit/Testcontainers/PostgreSQL tenant isolation and integration tests as part of scaffold tasks.

## Next hardening before Phase 01
- Configure main branch protection or ruleset with required checks and human review (verify repository/org plan permissions).
- Upgrade static secret scanning to full-history scanner and pin third-party actions to reviewed SHAs.
- Separate backend/frontend presence detection so one scaffold cannot accidentally avoid tests.
- Add enforced PostgreSQL/Testcontainers, Flyway migration safety, Spring Modulith, UI Playwright and SCA scan evidence once application is present.
- Confirm PR workflow checks show PASS in GitHub; a local YAML parse alone does not show runners succeeded.
- Never use workflow write permission, deploy, merge, or release steps in PR checks.
