# P01-T04 Authentication, identities and role/permission foundation

## Scope
Add Flyway V3 for global user identities, per-tenant memberships, tenant-local roles and permissions, opaque revocable sessions and access audit events. Only login, current identity, logout, and authenticated password change HTTP endpoints. This is an **internal foundation**, not a production-ready login or a user onboarding product.

## Security design
- Global username normalized lowercase and unique; single account may be assigned to multiple tenants through a controlled internal membership table (not a public API). Tenant is supplied during login, and a token represents exactly one authorized membership.
- BCrypt cost 12 password hashes; no raw password stored, emailed or logged. Password changes require current password, minimum 12 characters, and invalidate all sessions for the user. Initial credential provisioning is deliberately **not** exposed.
- Opaque cryptographically random 256-bit bearer token, SHA-256 hash stored in PostgreSQL with 30-minute fixed expiry. Tokens cannot be reconstructed from the DB. Logout revokes token; membership and account disable are checked on every request.
- Five failed login attempts lock an existing account for 15 minutes. Generic rejection messages avoid distinguishable wrong tenant/user/password responses. **This is account-level lockout, not comprehensive IP/device/distributed rate limiting.** Dedicated IP rate limiting, verified recovery, MFA and secure bootstrap are required before production.
- Spring Security: deny unspecified routes by default; no HTTP Basic, form login or browser cookie sessions. Stateless bearer API allows disabled CSRF token verification because no ambient cookie credentials are used. Only login is public; no registration or admin provisioning endpoints.
- New tenants/roles/permissions are NOT seeded automatically, no platform superadmin is granted clinical permissions. Role and permission records are tenant-scoped using composite foreign keys. API exposes roles and permissions only in /me; authorization for future facility/patient/clinical resources remains P01-T05.
- Audit LOGIN_SUCCESS, LOGIN_REJECTED, ACCOUNT_LOCKED, LOGOUT, PASSWORD_CHANGED without password/token contents. No real user/clinical data in tests.

## Threat and test review
Run real PostgreSQL Testcontainers integration tests for successful login, stateless access, invalid and revoked tokens, mandatory password change lifecycle, five-failure lockout, disabled memberships, absent tenant and cross-tenant role assignment rejection. Verify no raw token stored and no unauthorized /me access. Existing organization and Flyway tests must still pass.

## Outstanding gates
No self-registration, tenant onboarding, password-recovery channel, clinical role matrix, refresh token, distributed abuse throttling, MFA, secure secret-driven bootstrap, enterprise identity federation, session cleanup, per-user audit export or production client integration. All require approved future task cards. **P01-T05** must implement tenant-bound resource access, RLS and pooled-connection verification before any clinical data API. Do not claim login alone provides a compliant healthcare security system.

## Migration
V3 additive only; V1/V2 unchanged; `clean-disabled=true`. No production/customer data migrated or deployment authorized.
