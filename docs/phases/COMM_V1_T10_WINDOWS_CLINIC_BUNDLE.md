# COMM-V1-T10 — Windows Clinic Edition app bundle feasibility

**Owner task:** Development #91, Phase 07; Windows-first release, Online SaaS domain development in parallel.
**Status:** INTERNAL_FOUNDATION / NOT READY; no real clinical use, installer, original DB, deployment or merge.

## What is actually implemented
This independent branch packages **the actual current main Spring Boot backend executable JAR** and **the actually built React/TypeScript/Vite frontend static files** into one Windows Java 21 jpackage app image.

- Build-Clinic-Bundle-Proof.ps1 runs Maven package and npm clean install/build in the checked-out development source.
- Only compiled Spring Boot JAR, React static frontend and a small verification launcher are copied into a random TEMP directory for the Windows app image. The verifier checks Spring Boot BOOT-INF entries and frontend compiled JS/index.
- Java 21 app-image includes a bundled runtime and Windows .exe launcher. The bundled executable itself is executed by a disposable Windows GitHub runner and emits a build-only diagnostic.
- Production auth routes, Flyway migrations, clinical modules, test fixtures and database services are never started by the launcher. No patient data, test account, external server, original Windows database or cloud sync is involved.
- Read-only GitHub workflow uses contents:read, persist-credentials:false, no secret input or upload. Sensitive workflow still needs independent review before merge.

**Important:** Main's frontend currently shows only the AroNexa development scaffold. Separate Reception/Consultation/Rx/Billing PRs remain draft/unmerged and cannot be silently combined here. This increment proves **artifact packaging**, not a usable clinic UI or functioning offline database application.

## Acceptance
On isolated disposable Windows VM or GitHub Windows runner with JDK21, Maven and Node22 builder tools:

    & .\scripts\windows-clinic\Build-Clinic-Bundle-Proof.ps1 -RepoPath (Get-Location).Path

The script builds backend+frontend, stages Spring and compiled React artifacts, runs verifier before and after jpackage. It prints a Windows EXE SHA256. This is **not** a signed app or a distributable user installer.

**At authoring:** exact-commit Windows workflow NOT RUN. Only a successful Windows CI log can establish the packaged EXE launches and recognizes both compiled artifacts. GitHub standard CI Maven/frontend does not validate the Windows bundle by itself.

## Missing before real Clinic Edition pilot/release
- Real, integrated patient intake → appointment → consultation → Rx → billing and daily reports, including approved original registration/security gates.
- A Windows service/orchestrator to start/stop actual Spring backend and a bundled PostgreSQL 17 instance under dedicated least-privileged non-admin OS principal with locked-down ACLs, safe local-only listening and strong authentication.
- Actual installer/first-run wizard for nontechnical clinic staff, clinic admin setup, secure credentials/key custody, signed updates, uninstall/upgrade/rollback and failure diagnostics.
- Controlled annual offline license issuer trust/key custody, safe expiry without blocking clinical records, actual encrypted nonempty database backup and recovery, and rehearsed broken-disk/upgrade situations.
- Windows local firewall/antivirus, no mandatory network, clinic performance/printing, keyboard/NVDA and real doctor/reception usability tests.
- Independent security, clinical, privacy and local OS packaging signoff. Current GUI on main is foundation-only. Do not label this bundle a latest usable HMS increment.

## Dependencies / hard stops
- Development #31 original Windows V8→V9 W3-E/W4 migration HOLD remains.
- #45 independent patient registration review unresolved; #63 controlled manual merge and PR #36/#38 restrictions unchanged.
- Separate Windows crypto proof draft PR #90 remains unmerged; this branch does not copy that proof or its issuer keys.
- Separate encrypted PG17 recovery PR #93 is test-only and does not turn this app image into a recoverable clinic database.
- No patient/clinic DB, privileged Windows service account, application live migration, deployment, release or merge authorized.

## Master Engineering Contract V3 section 22 — 16-field handoff
1. Phase 07 / COMM-V1-T10, Windows-first bundle feasibility.
2. Baseline current main; isolated branch with no source/DB migration edits.
3. Spring Boot jar + React production assets + bundled Java 21 .exe app image.
4. No runtime DB/API change.
5. Frontend bundled but foundation-only; no clinical/stylus UI integration.
6. Executable checks artifacts only; production auth and Windows OS security NOT RUN.
7. Dedicated Windows CI prepared, exact-head result pending.
8. Standard Maven/frontend QA run separately by normal PR CI.
9. Indian privacy/clinical/packaging compliance signoff NOT RUN.
10. No supported customer installation or SaaS launch.
11. Java packaging verifier, PowerShell script, CI and V3 report.
12. No local PostgreSQL service, signed installer, first-run wizard or real OPD.
13. Backup/restore NOT IMPLEMENTED here; separate #92.
14. Doctor/frontdesk/keyboard/NVDA UAT NOT RUN.
15. INTERNAL_FOUNDATION / NOT READY.
16. Ready for isolated Windows QA; NOT READY FOR COMMERCIAL RELEASE.

Next bounded implementation: agree the signed Windows installer/service process and offline UX, then test a disposable local PostgreSQL 17 service with real authentication/authorized patient APIs, clinical operation and recovery. Never touch original Windows state until owner/security GO.
