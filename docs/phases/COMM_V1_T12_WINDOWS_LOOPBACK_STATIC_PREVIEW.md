# COMM-V1-T12 — Loopback-only Windows static Clinic UI preview

**Owner:** Development #95, Windows Clinic Edition #91/#74, commercial #72.
**Dependency:** STACKED on draft PR #94, baseline head 1608eaa495abd5fff6e096e7879d1f57a1be3f9f.
**Status:** INTERNAL_FOUNDATION / NOT READY. No protected release.

## Why this task is narrow

Current main React UI is a development scaffold. Clinical, registration, Rx and billing implementations are in separately review-gated drafts. Spring Boot requires explicit PostgreSQL configuration. Starting it against the original Windows database violates existing hold #31, so **this task does not start Spring, connect PostgreSQL, or serve any clinical API**.

## Delivered

- Existing T10 Windows jpackage build uses actual Spring Boot executable JAR and compiled React assets. This task adds a JDK21 local-only static frontend launcher, retains bundled-runtime packaging and tests the packaged executable.
- New ClinicLoopbackPreview.java entrypoint first verifies Spring Boot and Vite build artifacts without starting clinical services.
- --serve: binds exclusively to 127.0.0.1 on an available ephemeral port, prints the localhost URL, serves only static compiled frontend assets and runs until Ctrl+C.
- --selftest: GET root, HEAD index, GET a real JavaScript asset, verify no-store/nosniff/CSP and content type; deny non-existent paths, API endpoints, unsupported methods and traversal.
- Only root, index.html and whitelisted filenames under assets are accessible. Clinical API URLs and hidden files return 404, POST returns 405. No directory listing, no backend service, no patient files.
- Updated T10 PowerShell build script packages the new launcher and executes its self-test before and after Windows app-image creation.

## Disposable Windows verification

On a disposable Windows VM with the exact approved development branch, JDK21, Maven and Node22:

    & .\scripts\windows-clinic\Build-Clinic-Bundle-Proof.ps1 -RepoPath (Get-Location).Path

Then within the generated TEMP app-image directory:

    .\AroNexaClinicBundleProof.exe --serve

Open the printed localhost URL in the same disposable machine; Ctrl+C stops. The UI is the **foundation placeholder, not a clinical system**. The bundled Java runtime means this proof app-image does not require a preinstalled JDK on the target VM, although the build machine does.

**At authoring:** T12 exact-head Windows and Maven CI NOT RUN. Do not claim PASS from PR #94 prior runs. Stacked PR requires identical-SHA, disposable CI mirror if existing checks only target main; close such mirror after tests.

## Hard boundaries and missing commercial requirements

- This static UI is intentionally unauthenticated and contains no sensitive information. Do not add patient/doctor details, calls to real APIs, licence controls or backup access without real per-resource backend authentication/security review. Local Windows malware could access the public scaffold.
- Only the built backend *artifact* is bundled. Spring Boot is not started, no database is installed or connected, and protected clinical APIs are not accessible. CSP reduces browser network capability but does NOT provide clinical authorization.
- No signable clinic installer, restricted local PostgreSQL Windows service, first-run wizard, patient registration/consultation/Rx/billing workflow, data-at-rest protection, product licence, upgrade/rollback or verified real database restore exists in T12.
- Independent reviewer must assess source and executable. Existing #31 original Windows V8→V9 W3-E/W4 HOLD, #45 independent registration security, special #36/#38 and manual #63 merge gates binding. Online SaaS remains a separate security-reviewed deployment.
- No original Windows DB, PHI, real user data, production APIs, merges, external site, release or deployment touched.

## Master V3 section 22, 16-field handoff

1. Phase: 07, COMM-V1-T12 Windows internal localhost UI preview.
2. Baseline: PR #94 (unmerged) at 1608eaa495abd5fff6e096e7879d1f57a1be3f9f.
3. Deliverable: strict local JDK static UI launcher and automatic self-test.
4. API/DB: no changes; no backend process.
5. UX: compiled React scaffold only; OPD/stylus not integrated.
6. Security: loopback binding, static file allowlist, default-deny API, CSP and headers; no login.
7. Test cases: JDK selftest authored, execution pending.
8. Regression: exact-head existing Maven and frontend CI pending.
9. Clinical, financial, privacy and legal reviews: NOT RUN.
10. Deployment: internal app-image preview only.
11. Artifacts: new launcher, updated Windows packaging script, this report.
12. Risk: local unauthenticated static UI is NOT a security boundary for patient data.
13. Recovery: no DB backup or restore in this increment; separate #92.
14. Clinic UAT and NVDA: NOT RUN.
15. INTERNAL_FOUNDATION / NOT READY.
16. READY FOR DISPOSABLE WINDOWS QA; NOT CUSTOMER OR CLINICAL RELEASE.

## Next real development integration

Authorize real server-controlled authenticated P01 check-in, P02 encounter, Rx and P03 billing on a completely disposable local PostgreSQL 17 system, with active revocation, tenant/branch/doctor/object permissions, immutable audit, clinician signoff and Windows service/install/restore rehearsal. Do not use the original Windows database or merge protected drafts without separate owner GO.
