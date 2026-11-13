# R1-10B3C — Concurrency authorization contract (design-only)

**Status:** DRAFT / REVIEW ONLY; not implemented, not validated.  
**Issue:** #123. **Parent draft PR:** #122 at `1493379d18642eae5c2501c340e92be115307be6`.  
**Scope:** fictional test-only PostgreSQL 17, no production migration, route activation, installer, licensing, or release approval.

## Evidence-driven concern

The inherited R1-10B3B `require_live_staff` locks `auth_session` `FOR SHARE` before reading staff, role, branch and module permissions. The four tests establish rejection when an administrator already holds a conflicting **session** lock. They do **not** demonstrate consistent ordering when an administrator modifies role permission, branch scope, tenant OPD state, staff membership or doctor assignment *without* first coordinating on the same session. Booking's doctor read also occurs **after** the staff gate, without a corresponding doctor-scope lock. A session may expire during a long wait or command.

### Proposed contract — requires independent SQL/security review BEFORE implementation

Do not assume that adding `FOR SHARE` to arbitrary permission reads is sufficient: rows may be inserted, deleted, or replaced, and a missing row cannot be locked. Define stable scope anchors and lock the same anchors in EVERY booking/check-in and administrative mutation transaction.

Candidate lock order (all parties must use the same order, including account/session provisioning):
1. Tenant/module authorization anchor (one stable tenant row or durable purpose-built tenant policy version row).
2. Staff membership authorization anchor, and separately doctor membership anchor where applicable. Acquire membership anchors in deterministic UUID order to avoid cross-member deadlocks.
3. Branch/scope anchor for each affected branch, in deterministic UUID order.
4. Session row `FOR SHARE` for commands / conflicting write lock for revocation, **after** stable authorization anchors. Recheck session, expiry, all relevant role/permission, branch, module, patient and doctor state after all waits.
5. Appointment/idempotency and nonce rows in deterministic order where possible. Audit actual existing SQL's ordering; do not reverse a lock order between booking/check-in and admin flows.

**Critical migration constraint:** This proposed order differs from R1-10B3B's session-first lock. No code may follow this document piecemeal. First audit and design atomic changes for both administrative transactions and the guarded command SQL, with all affected read/write paths and provisioning/reassignment. If no shared stable anchors can be enforced across every write path, reject the design and keep production NO-GO.

### Linearization and expiration

- A revocation that commits before a command acquires the relevant shared authorization anchors must deny that command before any write.
- If a command has already acquired the anchors, an admin revocation may wait for command commit; that ordering must be documented and audited.
- Permissions must be re-read **after** any lock wait using appropriate transaction isolation; avoid cached snapshots/REPEATABLE READ assumptions.
- Session expiry is checked with `clock_timestamp()` at initial authorization and immediately prior to the irreversible write/replay return. Consider what happens if expiration occurs while waiting on appointment/idempotency locks, and define strict semantics.
- Verify all transactional effects roll back together on a denial, including nonce, events and check-in status. The test must assert the database, not only HTTP status.
- No lock wait should be unbounded. Define conservative `lock_timeout`/`statement_timeout`, retry behavior, and deadlock failure handling before enabling production.

## Admin write-path inventory required (not yet verified)

| Authorization state | Read by candidate SQL | Mandatory mutation-path search | Evidence needed |
|---|---|---|---|
| `auth_session.revoked_at/expires_at` | `require_live_staff` | Logout, forced logout, session renewal/rotation | lock ordering + expiry |
| `user_tenant_membership.enabled`, `app_user.enabled` | `require_live_staff`, doctor check | Staff deactivation/reactivation | all affected sessions |
| `auth_membership_role`, `auth_role_permission` | `require_live_staff` | Assign/remove role, modify role grant | shared anchor; multi-member role changes |
| `org_membership_branch_scope` | staff and doctor checks | Add/remove branch assignment | no phantom/race |
| `tenant_module_state`, `module_registry` | `require_live_staff` | Disable/enable OPD, entitlement/release | tenant-wide synchronization |
| Doctor membership, DOCTOR role and scope | `book_guarded` | Doctor deactivation/role change/branch removal | doctor authorization lock |
| Patient/registration status | `book_guarded` | Patient merge/deactivate/registration movement | decide whether authorization or integrity gate |
| Proof keys/nonces | `book_guarded`, `checkin_guarded` | Key disable/rotation, replay | key timing, no privileged bypass |

This table is a **review inventory**, not a claim that every admin implementation currently exists. Search backend controllers/services, migration procedures, bootstrap scripts and direct SQL maintenance paths; document missing or out-of-band writes. A system with unrestricted direct SQL administrative writes cannot assert strong revocation linearization.

## Required disposable PG17 test matrix

For each staff role removal, branch removal, OPD disable, staff disable, logout and doctor deactivation, test:
- Admin owns authorization anchor first; queue genuine signed restricted booking/check-in (confirm blocking via `pg_blocking_pids`); admin commits; command denies; zero new appointment/nonce/event.
- Booking owns authorization anchor first; queue admin revocation; assert documented serialization and next request denied.
- Different tenant and unaffected branch/staff proceed without unrelated blocking.
- Concurrent updates to two memberships in inverse command order cause no unhandled deadlock under deterministic locks.
- Permission **insert/delete**, not only update, plus replacement row races. Explicitly test absent rows.
- Session expires while queued: booking must deny when resumed, including after late appointment/idempotency wait.
- Check-in/replay/idempotency edge cases preserve exactly-once semantics and audit fields under denial.
- Under `READ COMMITTED` and any explicitly supported isolation configuration, verify re-read visibility. Fail closed for unsupported configurations.
- Privilege checks: runtime has no direct DML/SELECT on clinical/auth/key tables; definer owner `NOLOGIN NOBYPASSRLS`; no PUBLIC EXECUTE/schema CREATE; restrictive `search_path` and tenant RLS.
- Stress the same order repeatedly with bounded waits and report every failure, never disable assertions to green the suite.

## Acceptance / stop conditions

1. Independent reviewer signs off on the **complete** anchor model, deadlock order and admin/provisioning coverage.
2. Only after review: implement test-only versioned SQL overlay outside production Flyway and focused concurrency tests in a new commit.
3. Run clean exact-SHA Windows full regression with disposable Testcontainers PostgreSQL 17 and attach logs, actual counts and timestamps. Inherited 321 tests do not certify a new SHA.
4. Keep #31 Windows migration HOLD, #45 independent registration review, R1-09 nine NO-GO blockers and PR #122 Draft unchanged.
5. No application deployment, patient use, real production endpoint, merge, production V10/V11 or commercial release approval.

**Disposition:** R1-10B3C protocol proposal awaiting independent review; implementation, Windows process restart and customer pilot are NOT complete.
