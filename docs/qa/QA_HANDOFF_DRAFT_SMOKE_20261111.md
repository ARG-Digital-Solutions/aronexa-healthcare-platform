# Disposable draft QA handoff smoke — November 11, 2026

Test-only PR created against the updated trusted `main` workflow after PR #103 integration.

Expected: a draft PR with no `qa-plan:` label produces a successful `Central QA deferred (NOT TESTED)` job; privileged QA Central dispatch and result wait are skipped. This outcome is **not** independent QA acceptance, clinical security review, product release or permission to merge.

**Never merge this test PR.** Close after recording job evidence.
