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

## Version 3.0 release reporting and classification
Each phase is classified as INTERNAL_FOUNDATION, CUSTOMER_PILOT, PRODUCTION_RELEASE or NOT_READY based on executed evidence and user approval. A compilation-only result never constitutes production readiness. No release with unresolved exploitable critical/high vulnerability, unsafe clinical order, tenant leak, broken Rx integrity, financial reconciliation failure or failed applicable backup restore.

### Mandatory 16-field phase report (V3 section 22)
1. Phase and version.
2. Repository audit and reused functionality.
3. Features implemented.
4. Database/API changes.
5. UI/UX and stylus changes if relevant.
6. Security/clinical impact.
7. Test commands and actual results.
8. Regression evidence.
9. Compliance considerations.
10. Supported deployment modes.
11. Release artifacts.
12. Remaining defects.
13. Backup/recovery procedures.
14. Customer acceptance results.
15. Release classification.
16. READY FOR REVIEW or NOT READY recommendation.

### Deployment-specific validation
SaaS, Windows offline and dedicated installation have independent gates. SaaS passing does not prove offline or dedicated readiness. Offline clinical records remain available safely across license expiry, without exposing private signing keys. Backup restore is tested, not asserted. Apple/macOS and Android packaging only in their approved late phases. Report individual test results as PASS / FAIL / NOT RUN, with BLOCKED where an external prerequisite prevents execution.
