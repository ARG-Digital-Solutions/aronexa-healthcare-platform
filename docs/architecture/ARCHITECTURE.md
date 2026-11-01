# Phase 00 architecture proposal — review required
## Context
AroNexa is a unified configurable clinic/hospital platform; shared core for OPD, IPD, billing, pharmacy, diagnostics, IVF/IUI and specialty modules. Start with modular monolith, not microservices. Future SaaS, offline Windows, macOS and Android clients share domain semantics.

## Components and dependencies
React/TypeScript UI -> versioned REST APIs -> identity/tenant/authorization -> patient/appointment/clinical/prescription/billing/followup/ipd/pharmacy/lab/fertility/reporting/integration -> PostgreSQL/Flyway.

Modules own domain models and persistence; no cross-module repository access, circular dependencies or unreviewed shared mutable tables. Use published interfaces, transactional services, outbox when needed, and Spring Modulith verification.

## Tenant data protection
Shared database initially, mandatory tenant_id and composite tenancy-sensitive foreign keys, strong RBAC plus branch/resource checks, PostgreSQL RLS under non-owner/non-BYPASSRLS app role, transaction-local tenant context and pooled-connection negative tests. Fail closed on missing context. Separate migration role. Audit all sensitive changes and disclosures.

## Clinical integrity
Immutable finalized prescriptions with separately linked amendments, clinician identity/signature, patient identity verification, medication safeguards, structured encounter notes, audit trail, clinical version history and authorization before messaging.

## Security and deployment
Threat model authentication, secrets, session lifecycle, access escalation, uploads, backup encryption, retention, secure logs, tenant restore/export and disaster recovery. Regulations including Indian DPDP obligations and applicable healthcare/EHR norms require legal and clinical review before production.

## UI design
V3 approved navy #102D54, medical blue #2563EB, teal #0D9488, background #F5F8FC, white #FFFFFF. Role-based landing, low-click reception queues and OPD Smart Rx, keyboard/assistive technology support, meaningful status and responsive desktop/tablet. Reusable components across specialties. Stylus annotations retained with attribution and versions, not a replacement for structured medication orders.

## Decisions pending
Confirm master phase prompt, clinical regulation mapping, exact schema and integration requirements, SaaS vs offline tenancy model, and role/branch matrix before implementation.

## Version 3.0 authoritative amendments
Architecture must preserve a single shared patient/doctor identity with specialty extension entities, not specialty columns added indiscriminately to core patient/encounter tables. Include guardian/partner relationships, consent, clinical orders, shared billing, reports and longitudinal authorization. Restrict technical stack to low-cost modular monolith; use DB outbox and scheduler for notifications.

### Registry and authorization
Module registry tracks module/version, implemented/released status, dependency graph, tenant entitlement/activation, typed configuration and audit. Activate only released, licensed and clinically authorized modules. Hide disabled modules from ordinary navigation AND block their APIs; retain all historical records. Super Admin support to view medical data is exceptional, authorized and audited.

### Design tokens (V3.0; supersedes preliminary tokens)
Navy #102D54; Medical Blue #2563EB; Teal #0D9488; Background #F5F8FC; White #FFFFFF. Option A presentation + Option D workflow efficiency, keyboard and touch tests; role-specific Reception and doctor workspace first.

### Clinical ink architecture
Beginning Phase 02, separate structured clinical fields/orders from versioned ink overlays. Support mouse/touch/stylus, undo/redo, eraser/clear, zoom, draft recovery, clinical-diagram layers, printed PDFs and audited finalization/amendment; pressure and palm-rejection only when platform supports them. Never equate a picture of a signature with a valid digital signature.

### Deployment variants
ONE codebase with ONLINE_SAAS, OFFLINE_STANDALONE and DEDICATED_INSTALLATION adapters. Shared schema/business rules; environment-specific storage, secrets, communications, backups, licenses. SaaS plans/trials/renewal/downgrade/grace with patient-record continuity and export. Windows offline must install/launch without manual Java, PostgreSQL or Docker setup, offer local DB, printing, encrypted backup/restore and signed license files using asymmetric signatures (private signing key never shipped), with continued safe clinical access after expiry. Dedicated installation offers isolated database/storage/secrets/monitoring. Synchronization is NOT assumed; each deployment mode has separate acceptance tests.

### Platform order
Web/SaaS, then Windows offline/dedicated, then IPD/pharmacy/lab/IUI/IVF/integrations, finally macOS followed by Android; Android uses a mobile-appropriate client/API pattern, not a desktop PostgreSQL/Spring deployment.

### Scope and review status
These are architectural requirements, NOT implemented capabilities. Final threat model, data model, precise cloud/offline topology, regulatory applicability and signed clinical requirements remain Phase 00 review gates.

## Optional AI assistance module (P00-T10 proposed)
An internal provider-neutral module provides authorized, source-backed draft generation, prompt/template versioning, token/cost entitlements, output validation and audit. AI is not the source of truth; structured clinical records and authorized doctor decisions remain authoritative. Never automatically finalize treatment, prescriptions or discharge. AI-IPD-01 is Phase 09 priority; see docs/architecture/AI_ASSISTANCE.md. Existing non-AI workflows always function.
