# P00-T04 Security & patient-safety threat model — design, not test evidence
Status: PROPOSED. Requires dedicated technical, medical and regulatory review.

## Assets and trust boundaries
Assets: patient identifiers, clinical/Rx history, images/ink, documents, invoices, credentials, consent, encryption/backup and offline licence data. Trust boundaries: browser/mobile/desktop to API; API to app DB and files; tenant/branch/doctor boundaries; admin support; provider webhooks/outbox; offline local device and signed licence importer.

| Threat | Severity | Required controls | Future negative test |
|---|---|---|---|
| Cross-tenant patient disclosure via IDOR/query | Critical | Server ownership checks, tenant-scoped FK, least privilege and RLS | User A cannot read/write tenant B including guessed IDs |
| Leaked tenant context across pooled connections | Critical | SET LOCAL inside transaction; reset/fail closed | Interleaved tenant A/B tests, missing context denied |
| Super Admin unauthorized clinical read | Critical | No implicit patient-data grant, exceptional audited support | Admin lacking clinical permission is denied |
| Branch/doctor lateral access | High | Object-level assignment and branch policies | Doctor A blocked from non-assigned patient records |
| Credential and session abuse | High | Password hashing, revocation, expiry, MFA decision, throttling | Revoked tokens rejected; brute-force throttled |
| Forged file uploads/webhooks | High | Content validation, scan/size limits, signed webhook and replay checks | Malformed uploads and replay rejected |
| False or altered prescriptions | Critical | Clinician authorization, immutable final, linked amendments, audit | Post-final mutation rejected; wrong doctor denied |
| Stylus draft/data loss | High | Explicit drafts, versioned ink and restore | Crash/reopen/version/PDF parity tests |
| Unintended medical data disclosure by messaging | Critical | Consent/verified recipient, short-lived gated documents, outbox | Wrong recipient/no consent/expired links blocked |
| Duplicate billing/inaccurate refunds | High | Precise money, immutable adjustments, idempotency | Payment retry does not double charge |
| Offline licence bypass or lockout | High | Asymmetric signature; no private key on client; safe care continuity | Tampered file denied, expired licence preserves data access |
| Backup theft or failed restore | High | Encryption, access segregation, restore drill | Unauthorized restore denied and valid backup restorable |
| Fertility specimen mismatch | Critical | Identity checkpoints, dual witness as clinically required, custody ledger | Mismatched identifiers/custody transitions blocked |

## Verification approach
Phase 01+: use PostgreSQL/Testcontainers with application DB permissions and pooled transactions, role/branch/tenant negative tests, auth abuse tests, audit and permission assertions. Phase 02: prescription and ink failure testing. Phase 04: provider failure/replay/privacy scenarios. Phase 07: key management, offline backup and continuity. Phase 11: clinically approved identification/traceability tests.

## Compliance
Versioned applicability register: DPDP law/rules effective dates; practitioner/EHR obligations; ABDM if adopted; ART requirements; Maharashtra/facility-specific rules; pharmacy/lab rules; reporting and financial obligations. Legal/clinical signoff required before claiming compliance. No real patient data in dev/test. This document does NOT assert compliance or completed security testing.
