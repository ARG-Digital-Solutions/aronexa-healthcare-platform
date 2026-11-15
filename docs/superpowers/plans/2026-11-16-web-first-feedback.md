# Web-first feedback implementation plan

Goal: one discoverable entrypoint for the existing connected synthetic operator lab, safe marker-scoped STOP, and focused developer/feature checks. Reuse existing React/API/JUnit/PG17 code; add no UI, controller, migration or clinical activation. The user explicitly requests execution without repeated routine confirmations.

Architecture decision: wrap the existing Windows assisted Pilot09 lab, honestly labelled test-only; reuse strict Maven parser/child ownership helper. Developer checks are additional local feedback, not a QA-frequency/governance amendment. Feature checks use selected existing ordinary-profile HTTP/PG17 tests; all other required gates remain pending. No aliases for commercial approval.

Files: scripts/WebLab.psm1, Start-AroNexa-WebLab.ps1, Stop-AroNexa-WebLab.ps1, Test-AroNexa-Feature.ps1; behavior tests scripts/tests/Test-WebLab.ps1; docs/WEB_FIRST_DELIVERY.md. Existing product and WIN11P allowlists/workflows remain unchanged; this is a separate internal tooling branch and draft PR.

- [ ] Write failing tests: exact SHA refusal, tracked-dirty refusal, selected file/class plan, source-marked STOP and symlink/path/marker refusals.
- [ ] Implement wrappers/module; require genuine explicit synthetic scope confirmation to launch; never synthesize an independent receipt or operator observation.
- [ ] Run behavior tests, actual focused frontend and HTTP/PG17 selection, syntax/default-denial checks. Record all missing Windows/browser/operator tests as NOT_RUN.
- [ ] Produce source-backed bottleneck/integration/feature inventory, architecture, operating model, P0/P1/P2 backlog, packaging comparison and measured baseline with unknowns explicit.
- [ ] Commit code, verify exact new SHA, publish bounded draft PR and register separate QA planning request without dispatch/activation; no merge, clinical API/migration activation or release.

Review focus: no fresh lab wrapper may connect arbitrary DBs or kill other sessions; STOP only creates an owned lab control file; focused feedback never counts as full acceptance; logs stay private; no current-head evidence transfer.
