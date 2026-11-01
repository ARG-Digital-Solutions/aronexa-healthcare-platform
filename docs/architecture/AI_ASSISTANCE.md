# P00-T10 — AI-assisted healthcare architecture (proposed)
Status: DRAFT FOR REVIEW. Supplement to Master Engineering Contract V3.0, proposed Section 23; not a change to clinical decision authority. No AI feature implemented.

## Scope and sequence
AI-CORE-01 shared module design in Phase 00. Phase 02 optional OPD note drafting; Phase 04 patient message drafting; Phase 09 flagship IPD discharge-summary drafts; Phase 11 IUI/IVF cycle documentation; Phase 12 operational analytics. Phase 13/14 only reuse validated capabilities where appropriate. Conventional workflow always functions if AI is off/unavailable. No new separate phase.

## Architecture and data flow
Authorized UI -> existing clinical/application API -> AI orchestration boundary -> resource-level access -> minimal authorized encounter record snapshot -> template + versioned prompt + output schema -> provider-neutral inference adapter -> validated draft with provenance -> clinician review/edit -> existing authorized document finalization and immutable version history.

Keep inside modular Spring Boot monolith: ai.application (orchestration), ai.port (provider abstraction), ai.policy (tenant entitlement, consent, data minimization), ai.prompt (versioned templates), ai.audit (immutable event metadata). No direct AI database access and no automatic writes to clinical orders. Use existing module registry with AI entitlement, usage budget, role authorization and per-tenant opt-in. Avoid new external infrastructure unless justified.

## Clinical safety and privacy
- Human clinician approves all clinical outputs before final document/PDF release. AI cannot diagnose, prescribe, order tests, assert fitness for discharge or autonomously authorize a discharge.
- Construct a source-backed structured input from clinically entered, authorized facts. Flag missing, contradictory and unsupported statements; never invent lab values, results, doses, procedures or follow-up.
- Every draft retains patient/encounter/tenant binding, source-record IDs/version timestamps, template/prompt/model/provider version, clinician edits, generation timestamp and approval audit.
- Explicit data-processing review, provider contracts, residency/retention assessment and safe handling of sensitive health data before any identifiable external inference. Default development tests use synthetic cases.
- Provider errors, model timeouts, cost limit or offline modes fail safely to manual workflow; no fabricated success state.
- Protect against prompt injection from clinical free text and uploaded documents. Treat source content as untrusted data. Enforce output schema/content checks; never follow instructions embedded in records.
- Safeguard logging: no full clinical prompt/output in generic observability logs; tightly controlled retention and audit of authorized access.
- Version model, prompts and templates; use evaluation datasets with clinician signoff and rollback controls. Continuous review of inappropriate/unsafe outputs.

## Suggested governance
Draft storage and retention to be reviewed; separate customer subscription charging from hospital billing. Provide opt-out, provider disable switch and admin usage reporting without revealing clinical details. Maintain clinical incident escalation and post-release monitoring. Legal, clinical and privacy review required before actual patient use.
