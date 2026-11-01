# Phase 00–14 register (provisional)
Each phase: proposed -> approved -> in progress -> evidence verified -> owner accepted. Never advance automatically.
| Phase | Scope | Depends on | Complexity |
|---|---|---|---|
| 00 | inspection, ADRs, AGENTS, matrix, acceptance | none | M |
| 01 | patient, appointment, reception | 00 | L |
| 02 | OPD, prescription, stylus forms | 01 | L |
| 03 | finance, followups, reports | 01–02 | L |
| 04 | messaging, paperless | 03 | M |
| 05 | pilot security and clinical hardening | 01–04 | L |
| 06 | SaaS and subscription | 05 | L |
| 07 | Windows offline and licensing | 05 | L |
| 08 | dedicated installations | 05 | M |
| 09 | IPD | 05 | L |
| 10 | pharmacy, lab, stock | 02,09 as applicable | L |
| 11 | IVF/IUI | 02,10 as applicable | L |
| 12 | integrations and analytics | relevant modules | L |
| 13 | macOS application | 05,07 design | L |
| 14 | Android application | 05, API readiness | L |

## Phase 00 Codex tasks
- P00-T01 S: read-only repo access/tree, baseline SHA, branches, existing files and risks.
- P00-T02 M: compare exact consolidated master prompt; assign stable IDs.
- P00-T03 M: architecture decisions, Spring Modulith boundaries.
- P00-T04 L: tenant threat model, RLS, RBAC and clinical integrity.
- P00-T05 M: design tokens and reception/prescription flows.
- P00-T06 S: AGENTS.md review.
- P00-T07 M: task cards and dependencies for phases 01–14.
- P00-T08 S: documentary validation, review report and draft PR.

Task definition must specify scope, requirement IDs, files, exclusions, independent acceptance tests, baseline, verification command and risk. One task per Codex session for Plus efficiency.
