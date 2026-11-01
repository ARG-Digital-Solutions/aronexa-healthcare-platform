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
Medical navy #15324F, blue #176EA1, teal #0F9296, mint #DDF2E9, canvas #F5F8FB (proposed tokens). Role-based landing, low-click reception queues and OPD Smart Rx, keyboard/assistive technology support, meaningful status and responsive desktop/tablet. Reusable components across specialties. Stylus annotations retained with attribution and versions, not a replacement for structured medication orders.

## Decisions pending
Confirm master phase prompt, clinical regulation mapping, exact schema and integration requirements, SaaS vs offline tenancy model, and role/branch matrix before implementation.
