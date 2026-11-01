# GitHub branch protection / ruleset recommendations
Status: instructions only. This PR does not change repository settings.

1. Settings > Rules > Rulesets (or Branches > Branch protection rule depending on UI); target main.
2. Require pull request before merging and at least one authorized review if available; dismiss stale reviews when new commits are pushed. Single-developer operation may need a separate reviewer or choose explicitly recorded owner review instead of implying approval was enforced.
3. Require PR Quality Gates statuses: Documentation validation; PR scope and dangerous-file checks; Tracked secret patterns; Application CI (when scaffold exists). Select exact check names reported by GitHub, not guessed labels.
4. Require branches up to date before merging when feasible, block force pushes/deletions, limit bypass actors, disable auto-merge; apply to admins if supported.
5. Keep default GitHub Actions token contents:read; no deploy or merge action from PR workflow; pin third-party actions by audited full commit SHA before production.
6. Upgrade simple regex scanning to dedicated security scanner (secret + dependency/SCA) and ensure backend and frontend can never be skipped because only one is present.
7. When code exists, add explicit required backend, frontend, Flyway + PostgreSQL/Testcontainers tenant tests, Spring Modulith architecture tests, UI Playwright and security scans. Do not classify skipped jobs as tested.
8. Open a test PR to prove protection truly blocks a failed required job. Confirm settings manually; connector access in this task does not establish actual enforcement.

Operator acceptance: attach screenshots/settings export and test PR evidence in CI_GATES.md after setup; remain BLOCKED until independently confirmed.
