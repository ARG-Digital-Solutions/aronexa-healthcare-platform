# P01-T07-INT-REG-02A — Synthetic Reception contract preview

**Status:** IMPLEMENTED FOR REVIEW / INTERNAL_FOUNDATION / NOT_READY  
**Source baseline:** Frozen frontend PR #61, SHA 52809efbc077cd6abb6860d870cc487cc8eddc42  
**Owner authorization record:** Issue #66. **Separate backend baseline:** PR #38/#64, not merged or modified.

## Implementation and scope
- Add a pure, **not wired into App.tsx** TypeScript mapping from existing Reception Intake to the proposed INT-REG-01 C2 test-only DTO.
- UNKNOWN DOB maps to UNKNOWN/null; FULL DOB maps to EXACT/ISO yyyy-MM-dd. Existing UI validation and name normalization are reused.
- Nonempty phone is **blocked**, not silently discarded. Invalid names/dates and unknown precision are blocked.
- Proposed 201/200/409/401/403/413/429/503 results are classified without accepting an HTTP response body or exposing candidate details; unknown statuses and unknown 409 codes fail closed.
- Even the proposed 201/200 outcomes **cannot update** a Reception demo patient queue, create a fake MRN, or approve identifier remapping.
- Frozen Reception App.tsx, receptionModel.ts, appointmentModel.ts and all existing tests remain unchanged.

## Security / exclusions
The new module has **no network/fetch, authentication, browser storage, real patient information, identifier generation, backend, migration, Flyway, or production UI integration**. Do not import/wire it into App.tsx, assume a server registration was performed, or use it with actual patients.

Actual P01-T07-INT-REG-02/C3 live API integration remains blocked by independent security review issue #45, pre-merge governance issue #63, original Windows V8-to-V9 evidence/GO issue #31, and all INT-REG-01 gaps G-01 to G-10. The existing P01-T07-C2 production rollout remains NOT_READY.

## Acceptance evidence / limitations
- Targeted test command from frontend: node --experimental-strip-types --test test/registration-contract-preview.test.mjs
- Local isolated Node v22.16.0: **10/10 targeted tests PASS**. Targeted TypeScript noEmit strict type check: PASS.
- Evidence limitation: a local copy of the unchanged Reception model helpers was used for those local checks, **not a full fresh checkout or full Vite build of this exact PR**. Therefore do not claim full CI accepted.
- Full npm ci, Vite build, frozen Reception (26) and Appointments (20) regression, Playwright, security scan, QA Central exact-SHA run and independent reviewer: **PENDING**.
- The prior cross-repo QA PASS applies **only** to INT-REG-01 PR #64 exact commit 7f1fb76ce80a9fb838df06fc616e14f169bc2dd4, not to this branch.

## Independent QA handoff
Before marking this draft PR review-ready, prepare and independently review a new QA Central plan/workflow P01_T07_INT_REG_02A using this frontend baseline and three changed paths. Do not attach the old qa-plan:P01_T07_INT_REG_01 label or use old green CI. Keep this draft PR unmerged and do not deploy.
