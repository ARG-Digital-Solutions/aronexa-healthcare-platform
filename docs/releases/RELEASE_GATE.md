# Customer release gates
Release blocked unless explicit owner approval and:
- All task/phase acceptance evidence recorded, no critical/high unexplained defects.
- Successful appropriate unit, integration (real PostgreSQL), architecture and negative tenant tests.
- Security and secrets scans, dependency review, privacy/legal applicability review.
- Clinician-authorized safety checks for prescription/clinical records.
- Consent and audit trails for communication and disclosure.
- Verified backup restoration and recoverability where relevant.
- Installation, rollback, change notes, known issue report and customer training documentation.
- Customer UAT appropriate to delivered modules.
Never mark a phase complete on documentation alone except Phase 00.
