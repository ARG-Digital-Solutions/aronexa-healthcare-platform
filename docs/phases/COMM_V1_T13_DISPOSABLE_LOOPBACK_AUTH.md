# COMM-V1-T13 — Real Spring Boot authentication with disposable PG17

Owner task: Development #98; Windows Clinic Edition #91/#74; commercial baseline #72.
Classification: INTERNAL_FOUNDATION / NOT READY. No patient care or customer release.
Baseline: isolated main branch at b4d9e1ea9b5c6fdc3d2c29b561eba7d919a40e6f. No production Java/auth/Flyway/React source edits.

## Actual integration

This increment boots the real Spring Boot executable and three existing least-privileged runtime database identities against a freshly-created PostgreSQL 17 container on a disposable GitHub runner. PostgreSQL uses a random loopback-only host port and a new anonymous Docker volume that is removed at exit. The real Java backend starts with the current development profile, explicit 127.0.0.1 address and random port, and applies the existing Flyway V1 through V9 **only within this new disposable instance**.

The CI-only bash acceptance creates a unique fictional tenant/user/membership, a synthetic RECEPTION role and ORG_READ permission with a public test-only BCrypt password hash. It does not insert a patient, run the original Windows database or access customer credentials.

Separate compiled React frontend assets are served on a different loopback-only port. They are still an AroNexa **foundation scaffold** and are not joined to live clinical API behavior.

## Checks authored, pending exact-head execution

1. Refuse all non-GitHub Actions CI execution and check repository root and prerequisites.
2. Start a new randomly named PostgreSQL 17 Docker container with no permanent volumes and a loopback-only mapped port.
3. Verify three non-superuser, non-BYPASSRLS app/auth/patient SQL roles and compile real Spring Boot.
4. Start Spring on 127.0.0.1 using the new CI database, assert unauthenticated /api/v1/auth/me returns 401.
5. Insert a synthetic tenant, user and RECEPTION membership. Verify bad login 401, real BCrypt login 200 and a bearer-token-based authenticated /me response.
6. Verify server-derived tenant, membership, role and permission are unaffected by forged tenant/branch headers.
7. Assert the unapproved clinical endpoint returns 403 even for a valid session, and no cross-origin allow header is emitted for an untrusted origin.
8. Change synthetic password and verify prior token revocation; re-login with new password; logout and verify new token revocation.
9. Verify V9 schema on the **new CI database only** and empty patient master.
10. Open the compiled React foundation via a separate localhost static server and clean up all new resources.

Actual test PASS/FAIL must come only from a source-SHA matching GitHub Actions run. The test is not a clinical workflow, not a signed Windows installer and not an integrated offline UI.

## Security and product limitations

- Production Spring Security configuration, Flyway migration files, patient code and React code are unchanged. No patient-registration or OPD endpoint is enabled.
- The development profile uses an isolated migrator role for fresh CI-only schema creation and distinct app/auth/patient runtime SQL accounts. It must not be reused with the owner's original database.
- The public fictional test password/hash is **not** a real credential. Real clinic onboarding requires protected unique admin setup and key custody.
- No signed offline licensing, restricted Windows PostgreSQL service account, first-run wizard, in-app backup/restore, immutable patient/encounter authorization, real Rx/billing, integrated clinical UX, cloud sync or clinician UAT was implemented.
- Main React remains a development scaffold. Two localhost endpoints alone are not a customer-ready Clinic Edition.
- Independent security review remains necessary for any new clinical HTTP API or sensitive workflow changes.

## Governance

Development #31 original Windows V8->V9 W3-E/W4 migration HOLD remains. Development #45 independent patient registration security review and #63 merge gates remain. PR #36/#38 special holds and QA Central workflow restrictions remain. Previous Windows and encounter draft PRs are not merged or copied. SaaS continues as a separately reviewed release track.

No original Windows database, actual patient records, deployment, merge or customer release is authorized.

## Master Engineering Contract V3 section 22 handoff

1. Phase 07, COMM-V1-T13, offline backend readiness.
2. Baseline main b4d9e1ea9b5c6fdc3d2c29b561eba7d919a40e6f.
3. Actual Spring Boot, synthetic PostgreSQL, real authenticated bearer lifecycle acceptance.
4. Only a disposable PostgreSQL database, no changed application schema or clinical API.
5. Compiled React scaffold served separately; no integrated OPD or clinical ink.
6. Backend tenant authority, logout, password revocation, denied clinical routes and loopback checks.
7. Dedicated CI script authored; exact-head results pending.
8. PR Quality Maven/frontend regression pending.
9. Clinical, privacy and financial signoff NOT RUN.
10. No Windows installer, clinic data or SaaS deployment.
11. Bash acceptance script, read-only CI workflow, this handoff.
12. Risks: synthetic test credentials, no linked UI, no real Windows OS service isolation.
13. Recovery NOT RUN in this increment; separate #92/#93.
14. Clinic UAT/keyboard/NVDA NOT RUN.
15. INTERNAL_FOUNDATION / NOT READY.
16. Only CI and independent engineering review; no customer release.

Next milestone: separately authorize a real disposable, authenticated patient-check-in and encounter API integration once #45 security review and owner gates are resolved. Parallel Windows service installer and full-schema backup/recovery remain separate gates.
