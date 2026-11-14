# WIN-10 — Encrypted nonempty PostgreSQL 17 recovery rehearsal

**Parent:** Windows OPD pilot issue #124 and integrated Reception #135.  
**Baseline:** Draft Windows Assisted Pilot #140, source f02e0acbc8d3806abf2c9a74b31b952d1a8987d6.  
**Classification:** INTERNAL_SYNTHETIC_RECOVERY_PROOF, NOT RELEASE READY.  
**Current QA:** Windows and Docker execution NOT RUN on this new source.

## Purpose

Owner-reported RC03 technical acceptance covers browser-created fictional patient records recovered across separate JVMs in the same disposable database. Pilot-09 adds a held-open interactive Windows lab but destroys its database on shutdown. Neither proves authenticated encryption of a nonempty PostgreSQL backup or restore into an independently initialized database.

WIN-10 provides a *separate* crypto component and an isolated two-container PostgreSQL 17 rehearsal. It never contacts or migrates the original Windows database, installs no production schema or SQL grants, and creates no customer installer.

## Implementation

### Java backup crypto

Source: scripts/windows-recovery/ClinicBackupCrypto.java

- Java 21 only; no external library, secret service or CI credentials.
- AES-256-GCM, 128-bit authentication tag, per-backup cryptographically random 128-bit salt and 96-bit nonce.
- PBKDF2-HMAC-SHA256, fixed 450,000 iterations, derived 256-bit key. Versioned ARONEXA1 header is itself authenticated via GCM additional authenticated data.
- Interactive console reads a 16–1024 character backup passphrase, never passed as CLI argument, environment variable, printed output or file. Key bytes and character arrays are cleared where possible.
- Rejects symlink input, existing output, unknown format, empty input or unsupported version. Decrypted bytes are first written into a unique private temp sibling and published only after AES-GCM authentication succeeds; failure deletes the temp file.
- Selftest uses 32 KiB of random synthetic bytes: encrypt/decrypt SHA-256 equality, wrong-password rejection and ciphertext tamper rejection without publishing plaintext.
- CLI modes: selftest, encrypt INPUT OUTPUT, decrypt INPUT OUTPUT, roundtrip INPUT ENCRYPTED_OUTPUT RESTORED_OUTPUT.

This is *not* yet a reviewed clinical encryption/key-management service. Temporary decrypted plaintext exists during restore and cannot be securely erased by this test harness; use fictional data only.

### Windows nonempty database recovery test

Source: scripts/windows-recovery/Test-AroNexa-Win10-EncryptedRestore.ps1

- Requires Windows internal QA PC, Docker Engine, Java 21/JDK, exact Git source SHA, clean tracked tree, and explicit ConfirmFictionalIsolatedRecovery switch.
- Denies inherited PostgreSQL connection variables and Spring/AroNexa data source environment settings.
- Creates TWO uniquely named and labelled postgres:17-alpine Docker containers with network=none, --rm and NO mounted volumes; verifies identities and configurations using Docker inspect, including before cleanup. No host port is bound.
- Inserts three fictional FAKE-ONLY case rows in the source container. Proves there are exactly three rows and computes a deterministic ordered PostgreSQL row fingerprint. Destination is independently checked empty.
- Produces real PostgreSQL custom pg_dump, copies it to a unique private TEMP folder, encrypts it using Java in an interactive console, authenticates/decrypts it, and checks SHA256 of original and decrypted dump.
- Copies only the fictional decrypted dump to SECOND isolated PG17, runs pg_restore, verifies exactly three rows and the identical fingerprint. Plaintext fictional dumps and compiled helper are removed in finally. Source/destination containers are automatically removed after exact identity confirmation.
- Retains only encrypted synthetic sample FICTIONAL-PG17-backup.axenc and a sanitized SUMMARY.txt in the owner's private AroNexa-Local-QA/WIN10-guid folder.
- Final PASS marker is printed only after all steps. Staging or a failed test is not PASS.

## Exact SHA acceptance on Windows

Use a NEW clean detached Git worktree at the exact current WIN-10 Draft PR head. Run the following PowerShell command, replacing the SHA:

    $sha = "CURRENT_WIN10_DRAFT_PR_HEAD_SHA"
    powershell.exe -NoProfile -File .\scripts\windows-recovery\Test-AroNexa-Win10-EncryptedRestore.ps1 -ExpectedCommit $sha -ConfirmFictionalIsolatedRecovery
    if ($LASTEXITCODE -ne 0) { throw "WIN10 recovery QA failed" }

Type a NEW test-only 16+ character passphrase in the Java console. Never enter a real clinic database password or patient data.

Expected final exact marker:

    WIN10 ENCRYPTED NONEMPTY PG17 SECOND CONTAINER RESTORE QA PASSED

Review the evidence summary for both SourceRows and SecondContainerRows = 3, matching checksum and distinct container identities. The Java helper also prints:

    WIN10 AES256-GCM WRONG-PASSWORD AND TAMPER SELFTEST PASSED
    WIN10_BACKUP_ENCRYPTED_RESTORED_SHA256_MATCH

The wrong-password and tampering tests use **synthetic byte buffers**, not live medical backups.

## Explicit limitations

**A second PostgreSQL container is not a second physical machine.** The proof does not restore an actual AroNexa clinic schema or validate clinical audit trails, backup retention, remote media, power-loss recovery, encryption key custody/rotation, license continuity or Windows service startup. It does not establish independent security review.

Do NOT copy the crypto helper into a real clinical runtime without reviewed implementation, key-management and threat modeling. In particular, this does not approve PBKDF2 iteration calibration, immutable audit policy, ciphertext transfer/retention, Windows ACLs, stolen-machine defense, or second-PC restore.

**Still blocking customer release:** independently reviewed production runtime and SQL grants, original Windows V8-V9 HOLD #31, security #45/#123/#125, R1-09 NO-GO, restricted service installation/upgrade/restart, signed installer, offline license and historical read-only continuity, nonempty real-schema encrypted restore to another machine, clinical/accessibility staff acceptance and explicit owner GO.

Next: after owner exact-SHA local test, pursue **WIN-11 restricted persistent Windows runtime**, then WIN-12 real-schema encrypted second-device recovery, then signed Windows installer and UAT. PR remains Draft/unmerged. No customer deployment.
