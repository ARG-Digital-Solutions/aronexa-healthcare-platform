# R1-10A - Integrated OPD Runtime Foundation

Status: INTERNAL_FOUNDATION, source authored / local verification NOT RUN.
Parent: Development R1-09 #116 (read-only NO_GO, accepted exact source
4a380c7094fbd82e81faea7c03fcb842cb1ae03f), stacked on R1-08 #115
(277/277 real HTTP/disposable PG17, 10/10 mocked Chromium, owner PASS).
**Absolutely no commercial/pilot release authorization.**

## Critical finding - existing tested workflow is not production runtime

- Production Flyway migrations currently V1..V9.
- Registered /api/v1 registration GET is read-only. Registration POST is
  test-only (c2-synthetic/registration-candidate profiles).
- R1-04 through R1-08 appointment, consultation, Rx draft, billing and
  daily report HTTP handlers are in src/test only, with Testcontainers
  PostgreSQL 17-only SQL fixtures and privileged fixture setup.
- No supported general clinic runtime writes or SQL grants exist yet
  for the complete OPD patient encounter. The disposable test-only
  endpoints and migrations MUST NOT be exposed by removing profile
  guards or blindly copying fixture SQL.
- The old integration/clinic-opd-v1-20261111 source SHA
  249819d043503c20c3f2863894547fd98870db21 is distinct from R1-08
  head 965acbdbe0144e669942d5c30d8a5ce185129950 and must be
  reconciled by an explicit controlled merge strategy, not assumed
  to contain these features.
- Original Windows V8->V9 HOLD #31 and independent security review #45
  remain in force. No customer DB touched.

## What is actually implemented in R1-10A

1. Pure internal Java production domain value type
   backend/src/main/java/com/argdigital/aronexa/opd/OpdVisitContract.java.
   It records tenant/branch/patient/registration/appointment/doctor
   linkage and a minimal draft-only state sequence, with optimistic
   revision transitions and verified immutable visit identity.
   No Spring bean, HTTP route, SQL reader/writer, prescription issuance
   or billing action is exposed by the class.
2. Six unit tests in OpdVisitContractTests check positive sequence,
   direct billing after consultation (Rx finalization is not required),
   draft-only behavior, stale/out-of-order transitions, partial or
   zero-UUID identity rejection and cross-tenant/date/scope mutation.
3. Exact-source Windows runner
   scripts/Test-Opd-R1-10A-Integration-Foundation.ps1. It refuses a
   mismatched SHA, requires deny-all security with appointment-candidate
   profile guards, checks V9 production migration maximum, prevents
   accidental Spring runtime activation of the pure contract and
   delegates to the complete R1-08 HTTP/Testcontainers and frontend
   regression. This script has NOT YET RUN on Windows.

**Contract limitations:** This is a domain/activation-guard foundation,
not a real integrated OPD runtime implementation. Stage order is a
minimal customer workflow contract, not a complete clinical encounter,
prescription safety or accounting state machine. Actual SQL concurrency,
audit, session revocation and cross-tenant RLS still need runtime code
and real negative Testcontainers evidence.

## Controlled production implementation sequence

### R1-10B-1: Production schema authorization design

Develop and independently review forward-only post-V9 migrations, using
new production schema and indices. Do not replay the appointment_proof
synthetic test schema against customer records.

Required data model:
- One shared registered patient and branch-bound registration identity;
- Appointment with tenant, branch, patient, registration, doctor,
  visit date/slot, unique booking request and slot collision constraints;
- State, revision and append-only actor transition audit for booking,
  cancel, check-in and rescheduling;
- Doctor-only clinical draft revisions and immutable finalized versions
  once clinical signing is separately authorized;
- Versioned structured Rx DRAFT medicine snapshots without printing,
  signing, sending, dispensing or finalization at this increment;
- Branch ledger for server-priced OPD service bills, unique immutable
  bill/event references, idempotent partial cash and collection daily
  projection. Current monetary values in integer paise. NO legally
  issued invoices or actual payment provider.
- Consistent service-level linkage and FKs prevent cross-tenant and
  mismatched branch/patient/doctor associations.

Database roles:
- Migration owner MUST remain distinct from runtime login and schema
  writer. No BYPASSRLS/CREATEROLE/superuser/DDL for clinical runtime.
- RLS tenant AND branch/membership scope, forced where appropriate,
  least-privilege CRUD and restricted audit append-only permissions.
- Set transaction-local security context only from verified staff
  session and DB-revalidated membership. Check session revocation,
  module entitlement, organization branch scope and object ownership
  within the same authorized DB transaction.
- Prove roles' effective grants, tenant mixing in pooled sessions,
  forged IDs, session expiry, doctor assignment changes, replay, slot
  race, double bill, concurrent cash and clinical stale revisions.
- Rehearse on disposable PG17 first; original V8/V9 HOLD #31 remains
  until separately authorized.

### R1-10B-2: First user-visible vertical slice

Implement one **registered patient -> appointment booking -> check-in**
on real runtime APIs, existing authorized registration read, reviewed
registration writer and persistent DB. Evidence MUST include:
- Same fictional patient and registration ID remains visible after
  restarting Spring Boot and a new authenticated staff session;
- Wrong tenant/branch/role/registration/membership/expired token denied;
- Duplicate retry receives original booking; double-booked slot denied;
- Appointment branch queue derives from verified server context;
- Reception UI connects to those endpoints without synthetic fallback;
- Real HTTP, PG17 RLS and browser coverage at the same exact SHA.

**Do not activate** a real registration write until its existing
P01 security/key provisioning requirements are explicitly satisfied.

### R1-10C: Consultation -> Rx Draft -> Billing / Reports

Once R1-10B runtime and RLS are independently verified:
- Doctor opens only assigned checked-in visit, saves consultation and
  reopens versioned draft after restart; optimistic conflicts denied.
- Doctor saves structured prescription DRAFT, no issuance.
- Billing staff creates server-priced bill and idempotent fictional
  cash entry, daily collection sums are database-derived.
- Verify whole workflow survives restart, current authorization/revocation,
  and backend/SQL tenant isolation. Independently review financial controls.

### R1-10D: Windows packaging and verified recovery

Only after real runtime persistence, integrate separately reviewed
Windows app-image/crypto feasibility work and implement actual signed
installer, restricted local PostgreSQL service, assisted first run,
license continuity and encrypted nonempty backup/restore rehearsal.
Pilot release requires clinical, finance, security, UAT and owner GO.

## Scope and verification

R1-10A changes only a pure domain contract, six tests, a source guard
runner and this document. Production Flyway V1-V9, Spring HTTP security,
roles, profiles, database grants, installer, Git settings, deployment and
customer clinical records are not changed.

Owner run from a clean detached exact-SHA worktree after Docker Desktop
starts, with no Vite process using the same directory:

    powershell.exe -NoProfile -File .\scripts\Test-Opd-R1-10A-Integration-Foundation.ps1 -ExpectedCommit EXACT_40_HEX_SHA

Report actual tests/counts from the output. No new SHA acceptance or
independent human review is claimed until observed evidence exists.

Release decision: NO_GO, until INT-01, DB-01, SEC-01, WIN-01..04,
UAT-01 and REL-01 are independently evidenced and approved.
