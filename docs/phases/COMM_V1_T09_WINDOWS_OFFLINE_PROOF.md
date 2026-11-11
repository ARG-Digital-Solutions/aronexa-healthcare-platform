# COMM-V1-T09 — Windows Offline Protection and App-Image Proof

**Parent:** Windows commercial readiness #74, task #89, commercial execution #72.
**Release classification:** INTERNAL_FOUNDATION / NOT READY. **Synthetic data only.**
**Release sequencing:** Windows Clinic Edition V1.0 first; Online SaaS engineering in parallel from shared clinical/business rules. No offline-to-cloud synchronization in this proof.

## What this increment actually builds

1. Pure-JDK Java 21 Ed25519 signature **verification** for a fictional clinic license. Separate issuer private key is generated in unit tests or temporarily in CLI memory only; there is no private signing key or reusable license issuer in source, package, or runtime files.
2. Signed payload has fixed version, fictional clinic identifier, and inclusive expiry date. Tampered, wrong-clinic, malformed and invalidly signed payloads fail closed. An expired valid license reports EXPIRED_CONTINUITY; a license must never be used to hold clinical records hostage. Actual authentication, entitlement policy and clinical continuity behavior are **not wired into any application**.
3. AES-256-GCM plus PBKDF2-HMAC-SHA256 (220,000 rounds) cryptographic protection for **synthetic-only, maximum 1 MiB** example files. Random 16-byte salt and 12-byte nonce, full header authenticated as AAD. Wrong passphrase/tamper/truncation rejection. Outputs require a **new, non-existing file**. This is not a PostgreSQL backup, medical file utility or commercial restore feature.
4. A self-contained command-line synthetic demo, accepting **no user file or database arguments**, exercises signature check and encrypted fictional-file roundtrip, then deletes its temporary fixtures.
5. A Windows PowerShell proof script creates a standalone JAR containing ONLY the pure-JDK proof package and invokes JDK 21 jpackage to produce an **app image with a bundled Java runtime and console launcher** under a random Windows TEMP folder. A real MSI/EXE installer, service manager, automatic startup, PostgreSQL packaging and clinic UI are not included.

## Exact testing
From repo root (requires Java 21 / Maven and Docker for the full backend suite):
- mvn -B -f backend/pom.xml -Dtest=OfflineLicenseProofTests,SyntheticArchiveProofTests,OfflineProofDemoTests test
- mvn -B -f backend/pom.xml verify
- Frontend CI: cd frontend; npm ci --no-audit --no-fund; npm run build

The existing GitHub PR Quality Gates performs Maven verify, Testcontainers and frontend build, plus docs/scope/secret scans. **At initial authoring: new Java test counts and CI outcomes NOT RUN. Do not infer PASS from earlier T07/T08 runs.**

Windows build-only manual proof, **on a disposable clean Windows machine, NOT the owner's original Windows database host**:
- Clone/fetch the exact reviewed proof branch to a throwaway folder and verify SHA.
- Install JDK 21 and Maven 3.9+ on that **development/build VM**.
- Open PowerShell in the repository root and run:
    & .\scripts\windows-proof\Build-AroNexa-Offline-Proof.ps1 -RepoPath (Get-Location).Path
- Confirm the generated app-image executable exists in a freshly generated random TEMP folder.
- Disconnect the machine's network, open the EXE, verify the two proof PASS messages and ensure there is no network, DB or installer activity.
- Clean the **temporary synthetic proof folder only** after preserving redacted evidence. Never delete, reinstall or run migration scripts on the original AroNexa environment.
- Record OS build, JDK/jpackage versions, app-image SHA-256 and installer/uninstall status **NOT IMPLEMENTED**.

**This Windows app-image probe was NOT RUN on a Windows machine by the implementation author.** A green Linux CI does not establish it works on Windows.

## Critical security/design gaps before real backup/licensing
- Offline Ed25519 requires an independently controlled, pinned issuer public trust anchor and private signing-key custody/HSM/process; tests use ephemeral keys. Time/date on an offline machine can be manipulated; expiry clock rollback requires a privacy-preserving, fail-safe risk policy.
- The license verifier's continuity flag is **advisory**. Actual clinical record access, editing, emergency care continuity and product entitlements MUST use the authenticated clinic roles, audit and a separately approved, user-friendly expiry policy. Do not block access to prior clinical records due to a lost license or network.
- PBKDF2-GCM proof is **not** a production key-management architecture. No OS ACLs/DPAPI/key recovery, retention policy, streaming large PostgreSQL exports, pg_dump consistency, transaction logs, on-disk atomic recovery, failure-safe rollback, backup rotation, virus scanning or disaster recovery test is implemented. An encrypted file is not evidence of a recoverable database.
- Fixture marker rejects arbitrary clinical/DB content. The proof cannot encrypt or restore real patients by design and must not be reused as a clinical backup tool.
- jpackage app-image includes a Java runtime but **does not package the main Spring Boot app, PostgreSQL local service, Windows service account, clean first-run wizard, signed updater or full installer**. There is no customer-ready executable here; it is a developer feasibility proof.
- Windows-first product must avoid mandatory online activation and manual Java/PostgreSQL/Docker configuration for clinic staff, even though the proof builder needs developer tools.
- Original Windows V8→V9 W3-E/W4 HOLD #31 and independent registration security review #45 continue. No source migration, customer data, secrets, product grant, merge or deployment authorized.

## Windows acceptance matrix delta

| Gate | Current proof | Customer release requirement |
|---|---|---|
| WIN-01 Installation | App-image build script authored; Windows execution NOT RUN | Signed installer; fresh PC, nontechnical one-click setup, service control/uninstall |
| WIN-02 First run | NOT IMPLEMENTED | Clinic wizard, admin provisioning, least-privilege users |
| WIN-03 Offline OPD | NOT IMPLEMENTED | Real safe offline registration→OPD→Rx→billing, zero network dependence |
| WIN-04 Data isolation | NOT IMPLEMENTED | Backend auth, SQL RLS, Windows local service account and OS ACLs |
| WIN-05 Encryption | Small synthetic AES-GCM roundtrip and negatives | Real protected DB export, keys, privacy, secrets, audit |
| WIN-06 Restore | Small synthetic fixture roundtrip | Isolated nonempty PostgreSQL restore with row/audit identity and hash checks |
| WIN-07 Upgrades | NOT IMPLEMENTED | Signed versioned upgrade, forward migrations, recovery rehearsal |
| WIN-08 Licensing | Ed25519 verification test proof | Pinned issuer key, controlled renewal, expirations preserving essential care |
| WIN-09 Failure resilience | Tamper/wrong key/overwrite cases in proof | Disk-full, power loss, unclean exit, failed DB startup/print |
| WIN-10 Usability | No clinic GUI | Frontdesk/doctor acceptance, keyboard/tablet and low-click workflows |
| WIN-11 Release GO | NOT RUN | Security/clinical/recovery/UAT signoff and distinct owner GO |

## Master Engineering Contract V3 section 22 — 16-field report
1. Phase: Phase 07, COMM-V1-T09 Windows protection/app-image feasibility.
2. Repository: same AroNexa main foundation; separate branch from main.
3. Implemented: offline license crypto proof, synthetic encrypted fixture roundtrip and test CLI.
4. DB/API: none; no original Windows DB or Flyway modification.
5. UX/ink: clinic GUI and stylus out of scope; future installer must be simple.
6. Security: cryptographic primitive tests and negative cases; licensing trust chain/real backup NOT READY.
7. Tests: JUnit newly authored; actual execution pending CI.
8. Regression: full Maven/frontend and PR scope gate pending.
9. Clinical, India privacy/legal, installer compliance review: NOT RUN.
10. Deployment: isolated app-image proof proposal; no supported Windows/SaaS customer distribution.
11. Artifacts: three Java main classes, three JUnit suites, packaging PS1, this handoff.
12. Risks: offline clock rollback, missing key custody, platform ACLs, database recovery, signed distribution.
13. Backup/restore: **only synthetic 1MiB crypto proof**, PostgreSQL restore NOT RUN.
14. Independent security and clinic usability UAT: NOT RUN.
15. Classification: INTERNAL_FOUNDATION / NOT READY.
16. Decision: READY FOR EXACT-HEAD CI + DISPOSABLE WINDOWS PROOF REVIEW; NO CUSTOMER RELEASE.

**Next bounded step:** Build the app image in disposable Windows VM, then implement a real Windows clinic launch/service packaging ADR (signed installer + isolated local PostgreSQL service + safe localhost auth), licensing key custody/renewal, and true encrypted PostgreSQL backup/verified restore, each with separate exact-source tests and release authorization. Do not compromise original Windows state.
