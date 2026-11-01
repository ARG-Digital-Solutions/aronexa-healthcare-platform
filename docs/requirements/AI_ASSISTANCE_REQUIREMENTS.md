# Proposed Section 23 — AI-Assisted Healthcare Workflows
**Addition to Master Engineering Contract V3.0 — owner approval pending.**

AI is an optional drafting assistant, not autonomous medical decision-making. Product remains useful when AI is disabled, unsupported, unaffordable or offline.

| ID | Phase | Capability | Acceptance criteria before production |
|---|---|---|---|
| AI-CORE-01 | 00 design; implement with first AI feature | Tenant/role permission, AI opt-in, provider abstraction, prompt/template versions, generation audit, cost/budget controls and fail-safe manual fallback | Cross-tenant denial; provider unavailable uses manual path; traceable model/prompt/source metadata |
| AI-OPD-01 | 02 | Clinician-approved draft consultation notes (e.g. SOAP) using authorized encounter facts | No fabricated findings; clinician edit and finalize; unsupported claims flagged |
| AI-RX-01 | 02 optional | Improve language and readability of clinician-selected prescription instructions | Cannot create, alter or finalize medication orders; input/output validation |
| AI-MSG-01 | 04 | Draft patient reminder/educational text in approved languages and templates | Recipient/consent verification, human/template approval and provider delivery controls unchanged |
| AI-IPD-01 | 09 highest clinical priority | Source-backed IPD discharge summary drafting with discharge-type templates and clinician review | Draft-only; facts linked to source; contradictory/missing details flagged; finalization only by authorized clinician |
| AI-IPD-02 | 09 | Plain-language patient discharge instructions and clinician referral letters | Preserve authorized medication, procedures and follow-up; review required |
| AI-FER-01 | 11 | IUI/IVF cycle narrative summaries, consent and follow-up communication drafts | No treatment/embryo selection; partner privacy, custody and clinician signoff |
| AI-OPS-01 | 12 | Appointment trends/no-show explanations, stock trends and billing anomalies from authorized datasets | Analytics labelled as estimates; no automatic clinical/financial postings |
| AI-CLIENT-01 | 13–14 | Expose already approved AI drafting UI on supported clients | Same server auth, clinician review and fallback; device privacy verified |

## IPD discharge summary types
Routine medical, surgical/post-operative, maternity/obstetric, day-care, ICU/step-down, discharge on patient request and discharge against medical advice (DAMA/LAMA where used). All templates require clinician-approved headings and local institutional policy review. Consent/capacity discussions must be clinician recorded, never fabricated.

## Structured input and output
Input: patient/encounter identity, admission/discharge context, primary/secondary diagnosis, clinically recorded procedures, investigation findings, medication administration and discharge medicine orders, allergy, clinical course, follow-up plan and source versions. Must exclude unauthorized external records.
Output: sections, per-claim provenance where feasible, missing/conflict flags and draft status. Never assert fitness, diagnosis, medications, clinician advice or legal statements not supported by source notes.
Finalization: physician review/edit, confirmation of medication reconciliation, discharge instructions and follow-up, signed/authorized according to institutional policy, immutable finalized record with amendment history and PDF.

## Validation scenarios
Synthetic medical, surgical, obstetric, ICU and DAMA cases; mismatched patient IDs; absent discharge meds; medication discrepancies; contradictory dates; missing lab data; stale input after generation; prompt injection in physician notes; wrong-role/tenant; provider outage/timeouts; concurrent edits; accidental PHI disclosure. Require documented clinician acceptance thresholds and failure handling before production.
