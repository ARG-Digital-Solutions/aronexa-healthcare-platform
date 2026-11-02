# P01-T04 — Authentication, users, roles and permissions foundation

**Status:** READY FOR OWNER REVIEW — not merged, no deployment.
**Branch:** `feature/p01-t04-authentication-foundation`
**Draft PR:** https://github.com/ARG-Digital-Solutions/aronexa-healthcare-platform/pull/13
**Release classification:** INTERNAL_FOUNDATION — NOT READY for customer deployment.

## Change summary
- Flyway V3 adds accounts, tenant memberships, roles, permission grants, opaque-token session hashes and security audit metadata.
- Spring Security 7 default-deny filter, stateless bearer tokens, BCrypt passwords, 30-minute expiration, revocation, first-login mandatory password change.
- Private user-provisioning only (test/internal database) — NO public registration or default privileged account.
- Existing V1/V2 immutable, no database reset, customer migration or clinical features.

## Final verified GitHub CI evidence (commit a304fef; workflow run 41415815050)
| Test or command | Result |
|---|---|
| Documentation check, scope check and limited tracked-secret scan | PASS (3 jobs) |
| Docker Compose syntax and Docker preflight | PASS |
| `mvn -B -f backend/pom.xml verify` | PASS — **24 tests, zero failures/errors/skips** |
| Authentication HTTP+PostgreSQL integration | PASS — 10 tests |
| Existing organization/Flyway/architecture suites | PASS — 14 tests |
| Flyway V1/V2/V3 initial migration | PASS — schema version 3 |
| `npm ci --no-audit --no-fund`, `npm run build` | PASS |

## Historical failures and corrections
- First attempt: Java test-source escape issue (compilation failure). Corrected before successful CI.
- Second attempt: authenticated /me and logout rejected with HTTP 403 due servlet-path assumptions under MockMvc. Corrected with context-relative request URI; successful CI above.
- Added expiry and first-login route tests; an intermediate expiry fixture violated the timestamp constraint. Corrected. Latest run #41415815050 passed **24/24** tests, including both additional assertions.

## Outstanding tasks and risks
- No staff provisioning/onboarding, password recovery via verified channel, MFA, distributed IP/device throttling or robust account-lockout recovery; these are needed before production.
- Account-level failed login lockout is *not* a complete anti-abuse or denial-of-service control.
- Tenant/branch/object RBAC at clinical APIs and PostgreSQL RLS remain P01-T05; cross-tenant **role assignment** FK integrity is tested, not runtime clinical data isolation.
- No production secrets, patient data, deployment, customer migration, audit export or front-end sign-in workflow.
- GitHub organization ruleset configuration does not technically enforce PR checks on current plan; owner must manually review CI.
- No merge or P01-T05 until explicit owner authorization.

## V3 release gate summary
Phase P01-T04, software version remains scaffold foundation. No clinical features, UI/stylus changes, customer acceptance or production artifacts. V3/AI scope unchanged. Rollback for V3 must be a separate non-destructive migration; no Flyway clean. READY FOR OWNER REVIEW with final CI evidence; not production-ready.
