# P00-T10 AI safety, security and evaluation gates
Status: requirements only. All runtime/test statuses NOT RUN.

1. Build a synthetic and clinician-reviewed evaluation corpus; no real patient information in dev prompts.
2. Validate completeness, factual faithfulness, omissions, hallucinated findings, treatment contradictions and high-risk medication errors.
3. Require per-patient, encounter, tenant and branch binding; test cross-tenant/wrong-document isolation and revoked permissions.
4. Verify doctor review and authorization; AI cannot automatically create a finalized prescription, diagnosis, discharge order or signed discharge summary.
5. Test prompts containing instructions in untrusted patient notes or documents; ensure these cannot override authorization or output constraints.
6. Test structured output validation, missing data and contradictory records; draft must indicate uncertainty/missing sections.
7. Test provider failure, timeouts, network outage, cost limits, opt-out and offline Windows operation; manual clinical workflow remains usable.
8. Review external processor contract, locality/residency, retention, data-sharing legal basis and patient/institution consent configuration before PHI transfer.
9. Record source versions, clinician edits, prompt/model/template identifiers, review and audit; omit sensitive content from ordinary logs.
10. Require clinical, security and product owner sign-off. Report PASS/FAIL/NOT RUN/BLOCKED with commands and evidence and no unsupported claims.
