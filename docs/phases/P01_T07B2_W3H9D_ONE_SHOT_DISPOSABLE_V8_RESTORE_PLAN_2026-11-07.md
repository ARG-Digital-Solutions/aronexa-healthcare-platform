# P01-T07-B2 — W3-H9D One-shot disposable V8 recovery verification plan

**Date:** 2026-11-07 IST  
**Authorization to prepare:** owner said "proceed" after W3-H9C recommended **preparing** (not running) a fresh independently reviewed, disposable-only restore procedure, and asked why evidence work was displacing software increments.  
**Status:** `PLAN_PREPARED_NO_RESTORE_AUTHORIZED`. **NOT an execution script, W3 GO, W4 GO, or authorization to create/alter Docker resources.**

## Why this exists — and when to stop process work

AroNexa feature delivery has been displaced by a limited backlog of recovery/security/governance checks following V8→V9 development and accidental merges of PRs #27 and #30. Most security and build checks are already PASS in their tested scope: clean CI backend/frontend, fresh V9 dev startup, W3-H7 15/15 synthetic scenarios/rollback, W3-H9A evidence hash/marker audit. **Do not repeat these passing tests without a new concrete failure, changed code/DB, or independent safety need.**

**This is the final planned EX-01 recovery-proof decision** before returning engineering attention to the next customer-visible increment. The earlier W2A one-off real V8 restore **reportedly passed**; the contemporaneous log is missing. A fresh restore can replace the *current recoverability assurance*, but never reconstruct missing historical logs. Importantly, a backup that restores successfully can still be **stale relative to the current original V8**. Both facts must be recorded.

After EX-01 is dispositioned, complete a **single bounded W3 owner GO/NO-GO review** (EX-02 retrospective evidence; EX-03 one fresh already-completed W2C PASS; EX-04 manual/actual branch protection; EX-05 patient/customer release remains prohibited). Independently authorized patient-registration/duplicate-detection implementation can progress against **separate synthetic/test databases** without launching the newest application or Flyway against the protected original Windows V8. No blanket product-development freeze is implied by the W4 hold.

## 1. Inputs and known checks

| Item | Expected identity | Evidence level |
|---|---|---|
| Private W1 custom-format V8 dump | Exactly **92,567 bytes**, SHA-256 `153842CCC811A64729F2DBA0743515A6C24E5A20AFB1D054AF054FECB36C3CAB` | W3-H2/H3/H9A owner-reported PASS; keep original untouched |
| 25-row W1 V8 table baseline | 25 unique table names/counts; normalized earlier/later W1 and W2D CSV aligned | Owner-reported PASS; discover actual path, do **not invent a CSV basename** |
| Previous W2A restored V8 | Reported `FINAL_STATUS: W2A_DISPOSABLE_V8_RESTORE_AND_COUNTS_PASS`; 25/25 and V1–V8 Flyway checksums PASS | Earlier real `pg_restore --exit-on-error` operator-reported, historical transcript not located |
| Original source DB | Compose project `aronexa-dev`, service `postgres`, `aronexa_dev`, PG17/Flyway V8, synthetic-only | Reverify on the execution date, not by version assumption |
| Existing retained rehearsal | `aronexa-w2-pg17-20261106_100009_55d6f228`, network `aronexa-w2-v9-20261106_100009_55d6f228`, Flyway V9 | **Do not reuse, clean up, or alter** |
| Reviewed migration blob | V9 Git blob `3a1e2b717576ccd84f0de058bb0a83786c517d32` | Source identity only; **NO V9 migration in recovery test** |
| Prior W2C attempt | W3-H7 15/15 PASS, 1/1 consumed | **NO W2C rerun** |

## 2. Two decision paths (neither selected by this preparation)

**Option A — preferred if an independently reviewable restore receipt is required:** owner separately approves **one** disposable-only V8 restore with bounded read-only original checks, new isolated Docker PG17 target, native `pg_restore --exit-on-error`, pre/post counts/Flyway/role verification, protected local logs and no automatic retry. Review exact executable first. No V9 apply, no W2C, no original DB writes or original-volume access by the disposable target.

**Option B — proportionate for synthetic-only development if independent reviewer concurs:** do **not** create another disposable container. Instead independently examine existing W2A owner attestation + W1 integrity/H9A + current original-V8 snapshot alignment, formally accept the limited missing historical log risk for this scope, document recovery procedure, and proceed to a new owner W3 decision. **A missing original transcript stays missing under either option.**

**Option A adds time/resources and can expose separate operational issues; it is not automatically required if Option B is accepted.** The project owner must choose A or B in a specific new authorization, after reviewing the risks, rather than this "proceed" being treated as execution permission.

## 3. Mandatory preflight *for either decision* (read-only; separately reviewed)

1. Establish **exact original** Compose project/PG container/volume identity and single source, database name `aronexa_dev`, PG17, Flyway **V8**, no failed Flyway history, and affirmative synthetic-only dataset confirmation. Do not print patient rows, SQL credentials or secrets.
2. Establish clean reviewed Git branch/HEAD, migration blob identity and separately authorized operator/reviewer. Check that no Spring Boot process is positioned to auto-apply V9 against original; never run normal `spring-boot:run` or `docker compose up` on the original under W3-E HOLD.
3. Verify W1 file SHA+size, consistent archive header/TOC and 25-table CSV content; retain the existing dump and any old evidence **immutable**. Check private storage and ACL.
4. Reconcile **current live-original V8 table counts** with W1 25-table baseline and determine whether row/content changes could have occurred since the original W1 snapshot. Exact counts alone do **not** prove content identity. If divergence or uncertainty material to recovery is discovered: STOP, recommend a **new separately authorized consistent original backup**; never silently replace W1.
5. Freeze expected scenario: original DB read-only, no original role provisioning, no Flyway, no modification of retained W2 V9. Check Docker images and source/target labels, networks/mounts and host-port separation without mutating them.

## 4. Option A one-shot implementation design — not an executable instruction

- **Independent code review** of a new uniquely named Windows PowerShell 5.1 helper or independently reviewed copy. Do not rerun historical W2A unchanged. Script must separate preflight/read-only plan mode from explicit `-ExecuteOneAuthorizedRestore`; execution flag is absent by default.
- **Attempt receipt:** create a private `CREATE_NEW` receipt **before any temporary Docker creation/restoration** and bind to exact Git SHA, W1 SHA, operator consent, source identity and expected disposable name; reject a second attempt even after failure. No secrets or raw patient data in receipt.
- **Temporary isolation:** a **single new** uniquely labeled **internal-only** Docker network and a **single new** PG17 container with tmpfs or separately tracked disposable storage, no published host ports, no original named/bind volume, no connections to retained W2 V9 or original network; clearly distinguish it from `aronexa-dev`. Apply least-privileged disposable app/auth role *names* only where required to preserve ACL.
- **Restore only:** copy exactly the hash-verified W1 archive to the temporary container; run PostgreSQL 17 `pg_restore --exit-on-error` **once** into an empty temporary `aronexa_dev`. Fail closed for nonzero native exit, unexpected source or role/volume/network identity. **NO Flyway migrate, repair, clean, bootstrap of original patient role, W2C fixture or app startup.**
- **Verify:** restored Flyway V8 with valid V1–V8 history/checksums, 25 restored public table definitions and **25/25 identical row counts**, expected restricted app/auth principals/ACL, no failed migrations, and original Flyway V8 plus original source/container identity still unchanged at postcheck. Where feasible, compare minimally revealing per-table content/fingerprint using reviewed read-only queries to rule out equal-count changed rows. Do not print schema data or credentials.
- **Evidence:** private source/environment-isolation snapshot, exact attempt receipt, native restore exit code, sanitized 25/25 marker summary, independent SHA-256 file inventory and bounded output log (redact sensitive content). Never commit/private-upload the dump or raw logs. Record hashes and PASS/STOP only in issues #31/#33.
- **Failure handling:** no retry or automatic recreation after attempt; preserve evidence and created disposable resources pending separately authorized cleanup. **No `docker compose down -v`**, no deletion of the original or retained W2 rehearsal. Distinguish an environmental failure from proven backup corruption.

## 5. Acceptance and closure criteria

| Gate | Required finding |
|---|---|
| Source safety | Original identically recognized PostgreSQL V8, read-only checks only, synthetic-only confirmed |
| Backup | W1 SHA/bytes and CSV 25/25 independently checked; snapshot freshness/coverage explicitly judged |
| Isolation | New target unique and not attached to original/retained W2 networks, volumes or host ports |
| Restore if Option A approved | `pg_restore --exit-on-error` returns 0, 25/25 V8 counts, Flyway V1–V8 history and ACL/role expectations PASS |
| Evidence | Private contemporary exit and markers with exact SHA manifest; missing historical W2A transcript stays labeled `NOT_LOCATED` |
| Review | Independent technical reviewer provides EX-01 sufficiency/remaining risk and owner explicitly chooses `ACCEPT` / `REJECT` for **synthetic-only** scope |
| Governance | Owner W3-E HOLD stays in force until a **separate new W3 GO**, followed by distinct W4 procedure review and approval; PR #32 remains DRAFT/UNMERGED |

**Stop early:** if the original V8 source has drifted from the W1 snapshot, the safe next step is deciding a fresh original backup strategy, **not** restoring an obsolete dump and treating it as a migration rollback plan.

## 6. Product-delivery restart point

Avoid another series of identical evidence reviews after EX-01 is resolved. Put a bounded decision before the owner:

1. **Recovery adequately proven/accepted?** Record YES/NO with any narrow historic exception.
2. **Synthetic original V8→V9 migration allowed?** Only via new explicit W3 GO and separately authorized W4 controlled migration.
3. **Next working software increment:** separately authorize patient registration and duplicate-detection workflows (API + receptionist UX + tests + demo), using isolated synthetic DB/CI while the original V8 remains protected. Do not conflate that development with customer/clinical release.

**Owner W3-E decision:** `NO_GO_HOLD_APPROVED_2026-11-06`  
**W3 GO:** `NOT_GRANTED`  
**W4 original apply:** `BLOCKED`  
**PR #32:** `DRAFT / DO_NOT_MERGE`  
**Customer/real-patient release:** `BLOCKED`  
**One-shot restore execution:** `NOT_AUTHORIZED`  
**W2C rerun:** `NOT_AUTHORIZED (1/1 CONSUMED)`  

**Nothing in this document executes a command, creates Docker resources, changes databases, merges PRs or authorizes new functionality implementation.**
