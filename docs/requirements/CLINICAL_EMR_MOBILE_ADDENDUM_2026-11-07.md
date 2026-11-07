# Proposed addendum — Clinical EMR, patient 360, specialty documentation and mobile web

**Date:** 2026-11-07 IST  
**Status:** PROPOSED — REQUIREMENTS REVIEW REQUIRED; NOT IMPLEMENTATION AUTHORIZATION  
**Baseline:** AroNexa Master Engineering Contract V3.0 (authoritative, referenced by repository rules), existing Phase 00–14 register, and approved Option A + Option D UX direction.  
**Change type:** Documentation-only requirements integration; preserve prior requirement IDs, existing phases and accepted engineering work.  
**Important:** The standalone Master Engineering Contract V3.0 is not available as a repository file. This addendum is a proposed change for owner/clinical/security review and does not silently amend or supersede the signed source contract.

## 1. Clinical outcome and scope

A treating doctor, whether at the clinic or travelling, must be able to securely search for an authorized patient, open a concise patient 360 overview and find case sheets, visit history, investigation reports organized by **test name and date**, medications and treatment, patient photo (if permitted), specialty charts and prior signed documents without repeatedly asking the same questions. The doctor must be able to enter permitted new notes from an appropriate mobile web browser. Do not expose unrelated patient records or other tenants' data.

The platform stays a **single configurable modular monolith** using shared patient/clinician/encounter identities and specialty extensions, not separate databases or duplicate patient masters for fertility, obstetrics and IPD. This is a new requirement scope, **not** a claim of implemented capabilities.

## 2. Requirement-to-phase traceability

| New ID | Requirement and business behavior | Proposed owning module / phase | Dependencies and executable acceptance criteria |
|---|---|---|---|
| EMR-360-01 | Doctor-facing patient 360 screen shows patient identity, clinical alerts, recent encounters, diagnostics, active treatment, prescriptions, documents and upcoming follow-up with links into details; clinically important information visible at a glance without implying the entire record fits on one screen. | Clinical EMR / Phase 02; progressive additions in 04, 09–11 | PAT-01, SEC-01, CLN-01, UX-01. With synthetic longitudinal records, an authorized doctor can find last visit, latest result and treatment from one patient context; unassigned clinician, wrong tenant and restricted branches are denied. |
| EMR-HIST-01 | Longitudinal, date-ordered clinical record for case sheets, complaints, history, allergies, vitals, investigations, diagnoses, treatment, medicines and follow-ups. Prior answers are reviewable and can be explicitly confirmed or corrected, not copied as unquestioned current facts. | Clinical EMR / Phase 02 | CLN-01, RX-01/02, PAT-01. Show encounter source/date/author, record verified updates and amendments; finalized clinical records are not silently overwritten. |
| EMR-RPT-01 | Upload, categorize, view, download and search patient reports by **test name, test date, report type and encounter**. Distinguish clinical test date from upload date; preserve previous versions and provenance; connect formal lab results when Phase 10 arrives. | Documents / Phase 04; structured LAB integration Phase 10 | DOC-01, CLN-01, LAB-01. Authorized search returns correct chronological results, including multiple tests on one date; invalid/malicious file, wrong patient/tenant, revoked access and unauthorized download fail safely. |
| EMR-PHOTO-01 | Capture or upload a patient photo for clinical identity confirmation only where lawful/necessary, with access, consent/notice, retention and correction rules. No facial recognition requirement. | Patient EMR / Phase 02, after Phase 01 identity/security | PAT-01, SEC-01, REG-01. Authorized photo is shown with patient identity; unauthorized users cannot list/download original image; absence of photo never blocks clinical care. |
| MOB-CLN-01 | Responsive doctor web workspace on phone/tablet enables patient search, chart/report review and scoped entry of notes or treatment updates; efficient touch navigation and accessible status, with no implied early native app. | UX/Clinical / Phase 02, expanded in 04/09/11; native Android Phase 14 | UX-01, SEC-02, EMR-360-01. Test supported phone/tablet viewports, touch/keyboard accessibility, add/save/restore draft, and authorized read/write; no hidden automatic clinical finalization. |
| MOB-REMOTE-01 | Clinician travelling away from clinic can securely access permitted records through a properly deployed ONLINE_SAAS or approved DEDICATED_INSTALLATION endpoint; OFFLINE_STANDALONE is **not** automatically remotely reachable or synchronized. | Security/deployment / Phase 02 design, Phase 05 pilot validation, 06/08 environment readiness | SEC-01/02/03, SAAS-01, DEP-01. MFA/session expiry/revocation, auditable access, TLS, object-level authorization, safe handling of lost devices; unavailable network gives a safe, clear failure state, not guessed cached data. |
| DOC-SIGN-01 | Authorized clinician can review, attest, sign/finalize and amend clinical documents (Rx, discharge, OT note, IVF chart, partograph where applicable) with actor identity, timestamp, hash/version and change audit. Image/ink signature and verified electronic/digital signature are distinct workflows; determine applicable Indian legal and clinical signature rules before release. | Clinical forms & documents / Phase 02 contract, 04 shared document workflow, 09/11 specialty integration | RX-02, FORM-01/02, DOC-01, REG-01. Unsigned drafts editable; finalized versions immutable; amendment creates linked version, disallowed signer and post-finalization edits denied; PDF carries appropriate attestation metadata. |
| OBS-PARTO-01 | Editable, time-stamped digital labour monitoring chart/partograph recording authorized maternal, fetal, cervical and contraction observations, with medically reviewed visualization and alert rules; enable only for eligible obstetric facilities. | Obstetrics extension / Phase 09 | IPD-01/02, FORM-01/02, DOC-SIGN-01. Ordered time-series and corrected entries retain provenance, reviewed chart renders/prints, only qualified roles edit; no unvalidated automated diagnosis or clinical action. |
| IPD-DISCH-01 | Draft, review, approve, finalize, print and retrieve discharge card/summary linking admission, diagnosis, investigations, procedures, medications, discharge advice and follow-up. No autonomous discharge authorization. | IPD / Phase 09 | IPD-01/02, EMR-RPT-01, DOC-SIGN-01. Correct admission and attending clinician linkage, clinician review/signoff, immutable final, amendment history, controlled print/export; other-tenant and unauthorized discharge denied. |
| OT-NOTE-01 | Specialty-aware structured OT/procedure note with pre/post diagnosis, operation and anesthesia details, findings, complications, personnel, post-op plan and doctor attestation; linked to procedure and encounter. | IPD/OT extension / Phase 09 | IPD-01/02, FORM-01/02, DOC-SIGN-01. Draft/save/finalize/print, reviewer attribution and secure correction trail; wrong encounter/role cannot read or edit. |
| FER-CHART-01 | Editable IUI/IVF/ICSI-specific treatment chart with cycle identity, protocol, medication, scan/follicle and hormone monitoring, milestones, clinician review and downstream embryology/consent/chain-of-custody links. Keep IUI and IVF/ICSI distinct. | Fertility extension / Phase 11 | FER-01/02, LAB-01, FORM-01/02, DOC-SIGN-01. Multiple cycles per patient remain distinct; timeline, clinically approved template, signed snapshots and audit preserved; specialist/legal ART review gates pass before customer release. |
| SEC-EMR-01 | Across all new views/uploads/forms: server-side patient/encounter/tenant/branch/clinician authorization, module entitlement, audit, encrypted transmission and secured file storage; no public clinical files or real PHI in fixtures, screenshots or logs. | Cross-cutting / Phase 02 onwards; release gate in 05 and every customer increment | SEC-01/02/03/04, CORE-02, MOD-01, REG-01. Negative cross-tenant/branch/object tests, revoked clinician access, blocked module APIs, malicious-file scanning/validation, signed URL expiry/revocation, backup/restore and audit verification. |

**Priority:** P0 critical = EMR-360-01, EMR-HIST-01, EMR-RPT-01, MOB-CLN-01, MOB-REMOTE-01, SEC-EMR-01. P1 high = EMR-PHOTO-01, DOC-SIGN-01, OBS-PARTO-01, IPD-DISCH-01, OT-NOTE-01, FER-CHART-01. Priority is business sequencing only; no new coding is authorized here.

## 3. Implementation contract and data design (proposal only)

- **Shared entities:** Patient, tenant/facility/branch, clinician, encounter, clinical order, attachment/document metadata, document version, clinician attestation, audit event. Specialty records reference them by approved interfaces; no cross-module direct table ownership or new separate database.
- **Record storage:** Structured history, observations, test name/date, versions and indexes in PostgreSQL; encrypted deployment-appropriate object/file storage for report PDFs, images, photo and generated PDFs. Access must be authorized before serving bytes, not merely hiding UI links. Retention, malware scanning, file size/type restrictions, integrity checks, audit and backup/restore are design gates.
- **Clinical revision:** Distinguish draft, reviewed, finalized, amended and voided/entered-in-error states. Never rewrite finalized treatment records. Respect author, clinician qualification, encounter identity and signed version provenance.
- **Longitudinal viewing:** Current and historical facts are visibly distinguished; clinically significant alerts cannot be buried; clear author/date/test date and source. No automated prescribing, discharge approval or diagnosis.
- **Mobile delivery:** Responsive React web UI first; no Android/macOS native packaging until Phase 14/13. Device file/cache and token policy needs threat-model review. Remote connectivity for SaaS/dedicated does not grant offline Windows sync.
- **Signatures:** Clinician in-app authentication/attestation and stylus/drawn signature are NOT presumed to meet any legally required digital signature standard. Decide whether a compliant provider/certificate flow is required after legal/clinical review.

## 4. Stage gates and customer-visible outcomes

1. **Phase 01 prerequisite:** Continue authorized patient-onboarding work with existing W0–W5 migration evidence and explicit original-database GO gates. No new requirement changes P01-T07 scope.
2. **Phase 02 proposed increment:** Deliver clinician-usable synthetic-data patient 360/history/mobile view. Clinical safety/tenancy/accessibility tests and owner acceptance required.
3. **Phase 04 proposed increment:** Deliver medical report upload/index/search/preview, document versioning and signed-document workflows; validate with doctor UAT.
4. **Phase 05 clinic pilot gate:** Only approved core modules may be shown to customer with production classification supported by security, clinical, restore and operational evidence.
5. **Phase 09 hospital increment:** Clinically reviewed partograph, OT note and discharge workflow, individually entitled and release-gated.
6. **Phase 11 fertility increment:** Clinically/ART-reviewed IUI/IVF/ICSI charts with consent and chain-of-custody dependencies.
7. **Phases 13–14:** macOS then Android native application packaging, after shared APIs/workflows mature; no premature builds.

**Each subsequent implementation task requires its own owner approval**, bounded files, requirement IDs, executable acceptance criteria, negative/security tests, migration rehearsal when applicable, rollback/recovery and explicit release classification (INTERNAL_FOUNDATION, CUSTOMER_PILOT, PRODUCTION_RELEASE, NOT_READY).

## 5. Open owner/clinical/security decisions (not blockers to documenting the request)

- Confirm the clinical templates for labour charts, OT specialties, discharge and fertility with qualified clinicians and the intended facility context.
- Determine retention, consent/notice and photo necessity, and who may see clinical reports across roles and branches.
- Decide whether signer identity attestation is sufficient for specific record types or external legally recognized eSign must be integrated.
- Define mobile browser support, session/device security and connectivity requirements for travelling clinicians.
- Confirm deployment-specific storage, backup, file-scanning, applicable Indian privacy/EHR/ART and jurisdictional rules.
- Review additive requirement IDs and phase ownership against the original Master Engineering Contract V3.0 before considering this addendum part of the formally adopted baseline.

## 6. Scope and evidence status

**This document specifies requirements and test intentions only.** It does not claim code exists, tests were executed or signed clinical/legal clearance. No changes to application, database schema, migrations, installations, customer records, module activation, deployment, or production access are authorized. Keep the documentation PR in draft until independent owner review; never auto-merge.
