# COMM-V1-T11 — Disposable PostgreSQL 17 encrypted dump and restore

**Owner task:** Development #92, Phase 07. Windows Clinic Edition first, SaaS developed in parallel.
**Classification:** INTERNAL_FOUNDATION / NOT READY FOR CUSTOMER USE.
**Scope:** Independent implementation branch from main; no production migrations or real patient data.

## What this work implements
- Run-Disposable-Pg-Recovery.sh creates two newly randomized PostgreSQL 17 Docker containers, with network disabled, no host ports or volumes. Requires CI=true or explicit ARONEXA_DISPOSABLE_PROOF=YES, removes both containers on exit.
- Fixture SQL creates a fictional, nonempty two-tenant relational database with patients, encounters, Rx draft, invoice demonstration, immutable-style audit event, foreign keys, forced row-level security and synthetic schema history.
- Consistent pg_dump custom format is encrypted using a JDK21 Java-only AES-256-GCM/PBKDF2-HMAC-SHA256 proof helper. Small archives (maximum 16 MiB) only. Random generated salt, nonce and key per test, with the envelope header authenticated as associated data.
- Negative acceptance must reject incorrect passphrase, tampered ciphertext, truncated archive and attempts to overwrite existing output; no plaintext may be created on failed GCM authentication.
- pg_restore targets a second independent database container, not the original source instance; compares synthetic record digests, FK/RLS counts, checks nonowner tenant isolation and verifies the source digest is unchanged.
- Read-only GitHub PR workflow runs on disposable Ubuntu, Docker and JDK21; no repository secrets or customer connection strings.

## How to execute (disposable Linux/CI only)
CI=true bash scripts/recovery-proof/Run-Disposable-Pg-Recovery.sh

On a disposable Linux VM with Docker, OpenSSL, Python3, Java 21 and javac:
ARONEXA_DISPOSABLE_PROOF=YES bash scripts/recovery-proof/Run-Disposable-Pg-Recovery.sh

**Never use the original AroNexa Windows machine, patient records or database.** This proof uses neither host DB credentials nor network ports, but still requires a trusted disposable Docker host.

## Explicit limits of this proof
- At PR creation actual exact-commit workflow results NOT RUN; do not infer success until GitHub logs pass.
- Synthetic fixture is NOT the real AroNexa Flyway schema; synthetic proof_schema_history is NOT the actual Flyway ledger. The domain data are fictional.
- PostgreSQL cluster-global roles are not included in pg_dump; a test-only nonlogin role is bootstrapped separately in each instance and read grants reapplied after no-acl restore. It does not validate production role/key recovery.
- Maximum 16 MiB and in-memory crypto: NOT safe or suited to large production backups. No key rotation, clinic DPAPI/OS ACLs, secret recovery, WAL/PITR, offsite rotation, retention, crash-safe atomic encryption, disk-full/power failure, real source migration compatibility or enterprise recovery plan.
- Not a customer backup feature or Windows restore wizard. No real clinical authorization, consent, printing, signed prescriptions or billing release.
- The GitHub workflow and cryptographic design need independent security review. CI is evidence of a synthetic test only.

## Binding holds
#31 original Windows V8 to V9 W3-E/W4 migration HOLD. #45 independent registration security review NO-GO for patient-connected workflows. #63 manual merge gate; QA #11/#12 and PR #36/#38 specialized holds unchanged. No merge, deployment or customer use.

## Master V3 section 22 — 16-field report
1. Phase 07 / COMM-V1-T11.
2. Main as source; fresh isolated branch, no shared database edits.
3. Encrypted pg_dump/pg_restore rehearsal with synthetic metadata and RLS.
4. Ephemeral SQL fixture only, no application APIs or Flyway changes.
5. No Windows GUI/stylus; restoration UX pending.
6. Synthetic authenticated encryption, nonowner RLS test, production auth NOT RUN.
7. Automated workflow authored, exact-head execution pending.
8. Normal PR Maven/frontend regression pending.
9. Clinical/privacy/legal review NOT RUN.
10. No real Windows/SaaS deployment.
11. JDK crypto helper, fixture SQL, isolated bash runner, workflow and document.
12. Missing large DB streaming, key custody, crash-safe atomicity, customer schema recovery.
13. Only synthetic PostgreSQL 17 nonempty new-instance restore.
14. Clinic user, clinician and NVDA acceptance NOT RUN.
15. INTERNAL_FOUNDATION / NOT READY.
16. Ready for disposable CI and independent review ONLY, NOT commercial release.

Next production milestone: secure local PostgreSQL service packaging, streaming authenticated backup, pinned recovery keys, full AroNexa-schema restoration including actual role bootstrap and Flyway history, and safe failed-restore recovery; independent security/clinic signoff before customer release.
