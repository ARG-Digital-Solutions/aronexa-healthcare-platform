# P01-T04 — Authentication, users, roles and permissions foundation

**Status:** REVIEW IN PROGRESS — final expanded-test revision awaiting CI evidence.
**Branch:** `feature/p01-t04-authentication-foundation`
**Draft PR:** https://github.com/ARG-Digital-Solutions/aronexa-healthcare-platform/pull/13
**Release classification:** INTERNAL_FOUNDATION — NOT READY for customer deployment.

## Change summary
- Flyway V3 adds accounts, tenant memberships, roles, permission grants, opaque-token session hashes and security audit metadata.
- Spring Security 7 default-deny filter, stateless bearer tokens, BCrypt passwords, 30-minute expiration, revocation, first-login mandatory password change.
- Private user-provisioning only (test/internal database) — NO public registration or default privileged account.
- Existing V1/V2 immutable, no database reset, customer migration or clinical features.

## Verified GitHub CI evidence (commit c44acaf; workflow run 41415286931)
| Test or command | Result |
|---|---|
| Documentation check, scope check and limited tracked-secret scan | PASS (3 jobs) |
| Docker Compose syntax and Docker preflight | PASS |
| `mvn -B -f backend/pom.xml verify` | PASS — **22 tests, zero failures/errors/skips** |
| Authentication HTTP+PostgreSQL integration | PASS — 8 tests |
| Existing organization/Flyway/architecture suites | PASS — 14 tests |
| Flyway V1/V2/V3 initial migration | PASS — schema version 3 |
| `npm ci --no-audit --no-fund`, `npm run build` | PASS |

## Historical failures and corrections
- First attempt: Java test-source escape issue (compilation failure). Corrected before successful CI.
- Second attempt: authenticated /me and logout rejected with HTTP 403 due servlet-path assumptions under MockMvc. Corrected with context-relative request URI; successful CI above.
- Additional expiry and first-login route assertions added **after** the passing 22-test run; expiry fixture corrected to satisfy database timestamp constraint. **Final expanded-test revision NOT YET VERIFIED by CI**. No PASS claimed for these two assertions until the subsequent run completes.

## Outstanding tasks and risks
- No staff provisioning/onboarding, password recovery via verified channel, MFA, distributed IP/device throttling or robust account-lockout recovery; these are needed before production.
- Account-level failed login lockout is *not* a complete anti-abuse or denial-of-service control.
- Tenant/branch/object RBAC at clinical APIs and PostgreSQL RLS remain P01-T05; cross-tenant **role assignment** FK integrity is tested, not runtime clinical data isolation.
- No production secrets, patient data, deployment, customer migration, audit export or front-end sign-in workflow.
- GitHub organization ruleset configuration does not technically enforce PR checks on current plan; owner must manually review CI.
- No merge or P01-T05 until explicit owner authorization.

## V3 release gate summary
Phase P01-T04, software version remains scaffold foundation. No clinical features, UI/stylus changes, customer acceptance or production artifacts. V3/AI scope unchanged. Rollback for V3 must be a separate non-destructive migration; no Flyway clean. READY FOR REVIEW only after final CI passes; not production-ready.
