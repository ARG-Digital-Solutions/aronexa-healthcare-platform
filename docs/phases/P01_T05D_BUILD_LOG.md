# P01-T05-D — Adversarial isolation and authentication privilege review

**Status:** IMPLEMENTATION IN PROGRESS — tests and CI pending.
**Branch:** `security/p01-t05d-adversarial-auth-isolation`
**PR:** Draft PR to be opened.
**Release classification:** SECURITY FOUNDATION ONLY; not production-ready.

## Planned verification
- Flyway V6 applies after separate runtime auth role bootstrap.
- Organization runtime role cannot directly SELECT/UPDATE app_user, auth_session, role grants, or tenant membership.
- Authentication runtime role cannot SELECT branch/facility/department; SQL auth login/logout works.
- Concurrent tenant isolation and pooled-context cleanup.
- Existing authentication/organization RLS/authorization regression and frontend build.
- CI documentation, scope and tracked-secret checks.

## Known boundaries
No clinical data models, patient/doctor/encounter IDOR validation or clinician sign-off. Global authentication account privilege model is not tenant-isolated; separated role minimizes blast radius only. No production migration or deployment. Require owner PR review and security risk acceptance before merge.
