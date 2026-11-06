# P01-T07-A — V8 technical acceptance and closure review (2026-11-06 IST)

**Review decision: Technical V8 Verify PASS based on owner-supplied Windows evidence. Formal task closure PENDING owner approval.**

This is a documentation-only record, not a production release or authorization for P01-T07-B.

## 1. Phase/version
P01-T07-A; shared patient identity and registration schema foundation; Flyway V8. Release classification: INTERNAL_FOUNDATION.

## 2. Repository audit
PR #25 (https://github.com/ARG-Digital-Solutions/aronexa-healthcare-platform/pull/25) merged into main SHA 4f5926a883506437bf7131b792ee39fd8c7579da. Existing multitenancy, Flyway, PostgreSQL restricted roles, RLS, Java/Spring Boot, React/TypeScript reused. No application code modifications are proposed by this closure work.

## 3. Implemented features
V8 schema: patient, patient_identifier, patient_contact, patient_relationship, patient_registration, patient_identity_audit. This is schema-only. No patient-facing registration workflow, API, duplicate matching, MRN generator, consent, OTP or guardian verification is accepted.

## 4. Database/API
Owner-reported original V7 to V8 successful migration following a disposable rehearsal; Flyway version 8, zero failed migrations, V8 successful, six new tables, preservation of counts for all 18 pre-existing tables. No new runtime clinical API or table access granted.

## 5. UI/stylus
NOT IN SCOPE / NOT RUN.

## 6. Security/clinical effect
Owner-supplied read-only verification: FORCE RLS for relevant patient tables, no application runtime grants, no patient records. Wider auth-role security, MFA/recovery, abuse throttling, clinical authorization and patient-consent gaps remain open. No real patient data or customer approval.

## 7. Commands and actual results
Owner ran Windows acceptance Prepare, separately authorized Apply, evidence-only finalization, then Verify with Maven and npm wrappers corrected for PowerShell 5.1 native-stderr warnings. Final Verify used the locally generated Invoke-AroNexa-V8-Acceptance.VerifyNpmFixed.ps1, not the earlier uncorrected scripts. Reported Maven BUILD SUCCESS, 11 Surefire XML reports, 71 tests, zero failures/errors/skips/parse errors; npm ci and frontend production build PASS. Latest report result:

    FINAL_STATUS: READ_ONLY_V8_VERIFICATION_PASSED; OWNER_CLOSURE_APPROVAL_STILL_REQUIRED

The assistant did NOT run these commands against the original Windows machine or independently fetch the raw local reports.

## 8. Regression evidence
Original database, backup/manifest, table preservation, Flyway, RLS, grants and synthetic Testcontainers verification were reported PASS. Earlier blocked Verify attempts from Mockito and npm stderr are historical report entries, superseded by the final passing Verify; they remain documented.

## 9. Compliance
Synthetic development-only acceptance, not Indian healthcare compliance certification, privacy signoff or clinical validation.

## 10. Deployment modes
Verified within owner-supplied Windows development acceptance scope. SaaS production, customer dedicated/on-prem, standalone Windows installer, macOS, Android: NOT RUN.

## 11. Evidence/artifacts
See evidence/P01_T07A_20261106/EVIDENCE.md. Original backup, manifest and private build logs remain off Git. Canonical final acceptance script has been committed from the owner-uploaded final VerifyNpmFixed file to `scripts/acceptance/p01-t07a/Invoke-AroNexa-V8-Acceptance.ps1`. Uploaded SHA-256: `2BCA93D6A83ACAB8F4071541E51B3990F277B103FECF5A5F47B042B49AEEB933`. Exact GitHub file text matches the supplied original. See scripts/acceptance/p01-t07a/README.md.

## 12. Open limitations
- Final owner-uploaded PowerShell script and SHA-256: COMMITTED TO **draft PR #27**; GitHub content matches the upload. The owner separately executed `Get-FileHash -Algorithm SHA256` on the locally successful `VerifyNpmFixed.ps1` and reported the matching value `2BCA93D6A83ACAB8F4071541E51B3990F277B103FECF5A5F47B042B49AEEB933` on 2026-11-06 IST. Independent checksum of the uploaded file also matches. Static checks found no embedded private key or hardcoded personal absolute Windows path; independent Windows PowerShell 5.1 execution of the Git copy was NOT performed.
- Historical Apply manifest assignment bug remains present in this exact final acceptance script (`$m.applied_at` property assignment after JSON conversion). It caused the original post-migration evidence failure and is NOT fixed by Maven/npm wrapper changes. **Do not rerun Apply.** A safe generalized fix requires a separately authorized task.
- Private raw evidence ZIP inventory and SHA-256 attestation: OPEN.
- Latest GitHub CI status: NOT REVIEWED here.
- Mockito Java-agent future-JDK issue remains open; PowerShell stderr mitigation is not a Mockito version fix.
- npm install-scripts policy warning remains for dependency-policy review; no blanket allowScripts approval occurred.
- P01-T07-B patient onboarding features, authorization, duplicate matching and clinical workflows: NOT IMPLEMENTED.

## 13. Backup/recovery
Owner-reported pre-V8 dump: aronexa_dev_pre_v8.dump, 58,043 bytes, SHA-256 E2B7CD2E6CA1C5198AD43536B0EA53884FCC2F491F2202D274B2CDB9C6D58A3B. Disposable V7 restore and V8 rehearsal: PASS per owner. Operational recovery cutover on original database: NOT RUN. Do NOT rerun Apply, restore, clean, repair, downgrade or modify the original V8 database for this closure review.

## 14. Customer acceptance
NOT RUN. No patient-facing usable product increment delivered in this task.

## 15. Release classification
INTERNAL_FOUNDATION. Technical V8 verification: PASS within owner-supplied scope. Owner formal task closure: PENDING. Full Phase 01: IN PROGRESS. Customer/production: NOT READY.

## 16. Recommendation / formal decision
READY FOR CLOSURE EVIDENCE REVIEW, NOT YET OWNER_ACCEPTED. Merge and formal closure require final script review, sanitized private evidence inventory, known-risk acknowledgement and explicit owner approval. PR creation is not owner closure approval or merge authorization.

## Closure gates

| Gate | State |
| --- | --- |
| Preflight backup / disposable rehearsal | PASS, owner supplied |
| Original V7-to-V8 / Flyway | PASS, owner supplied |
| 18 preexisting counts / six patient tables / RLS / runtime grants | PASS, owner supplied |
| 71 backend tests / Maven / npm frontend production build | PASS, owner supplied |
| Final read-only Verify report | PASS, owner supplied |
| Canonical owner-uploaded script archived in Git | COMMITTED; byte-identical text; independent runtime retest NOT RUN; review ongoing |
| Private evidence archive hashes and completeness | OPEN |
| Formal P01-T07-A owner closure approval | PENDING |

**Prohibited scope:** P01-T07-B, new patient API/UI, customer deployment, real patient use, schema/data changes, new migrations and automatic merge.