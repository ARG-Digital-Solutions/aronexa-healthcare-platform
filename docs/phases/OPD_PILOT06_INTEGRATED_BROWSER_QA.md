# R1-PILOT-06 — Integrated Chromium/real-HTTP OPD Candidate

**Status:** new Draft PR implementation; not yet run on owner's Windows PC. Never use real patient data.

## Product increment

A single **test-only connected Reception preview** now carries server-verified, in-memory routing references across:

1. **Reception** (separate authorized session): daily queue, server-confirmed check-in and verified/logout token revocation.
2. **Doctor** (fresh assigned-doctor session): branch/appointment prepopulated, read-only; authenticated consultation context and patient identity rechecked; consultation draft persisted.
3. **Prescription**: structured *fictional-only* medicine draft saved and independently verified against consultation revision; cannot hand off while unsaved local edits remain.
4. **Billing** (fresh separate billing account): patient/tenant/appointment/doctor/branch matched to independent billing-context GET. Bill created using server-priced fee; daily collections read from the authorized server.

**No tokens, names, medical notes, billing amounts or credentials are passed between the roles.** The UI routing hints carry only synthetic UUID references and two revision numbers. Strict logout verifies HTTP 200/204 then a 401 on the revoked token's /me endpoint before transitioning. This remains test-only; no customer clinical or financial operation is enabled.

## Browser acceptance — actual backend, not mocks

The backend test method `AppointmentCandidateHttpAcceptanceTests#pilot06BrowserIntegratedRealHttpAcrossThreeStaffRoles` owns a **disposable Testcontainers PG17** and Spring random-port test controller. It seeds one fictional patient/registration, reception/doctor/billing roles, and a BOOKED appointment through authenticated HTTP. The targeted browser test launches **Vite Preview** against the already-built `dist-connected-registration-test` artifact on `127.0.0.1:5176`, with its explicit `preview.proxy` forwarding `/api/v1` only to the random **loopback test port**. It avoids running Vite's development transforms during backend acceptance. Chromium performs actual clicks from daily queue → check-in → doctor → consultation → structured prescription draft → billing → bill → daily report. The test refuses stale UI port reuse, verifies actual success HTTP statuses, and independently checks SQL consultation, Rx and invoice rows after browser automation.

**Scope limitation:** this browser test starts from a **server-seeded existing patient and BOOKED appointment**; it does not validate new patient registration UI. It also does not verify printing, digital signatures, WhatsApp, real payment, backup, or Windows installer. A separate browser registration/new-booking scenario is still required.

## Prerequisites

- Windows PowerShell, Java 21, Maven, Node/npm, Docker Engine
- Private development repository clean worktree at **the exact current Draft PR SHA**
- QA Central Playwright checkout with `npm ci` and installed Chromium:
  `C:\Users\<WindowsUser>\Application Development\AroNexa-QA-Central` or provide `-QaCentralPath`
- QA Central Playwright package is reused **without modifying the product npm lockfile**
- Allow 127.0.0.1 ports 5176 and a random backend port; no other Vite server on 5176
- No inherited `SPRING_DATASOURCE_*`, external Spring profile, or PILOT06 test variables

Run from the **clean exact-SHA** repo root:

```powershell
powershell.exe -NoProfile -File .\scripts\pilot\Test-AroNexa-Pilot06-IntegratedBrowser.ps1 -ExpectedCommit <EXACT_SHA> -QaCentralPath "$env:USERPROFILE\Application Development\AroNexa-QA-Central"
if ($LASTEXITCODE -ne 0) { throw 'PILOT06 QA FAILED' }
```

The runner performs `npm ci`, all frontend unit tests, three test-only connected Vite builds, the targeted real-HTTP browser JUnit test and validates exact Surefire XML (one pass, no failures/errors/skipped). A green result must end with:

`PILOT06 INTEGRATED BROWSER REAL HTTP QA PASSED`

On failure, capture first error / `PILOT06_BROWSER_FAILED_STAGE=...` and Surefire report. **Do not report PASS** if build, browser, HTTP verification or SQL readback fails. Browser fixture credentials are delivered to Node process via stdin, never in CLI arguments or logs. Browser engine and Node/JUnit tests use test-only accounts.

## Exclusions and release gates

This proof is **not** an installed Windows service, production runtime migration/SQL permission proof, permanent encrypted backup/restore, signed installer, clinical prescription issuance, real payment, independent security/accessibility/doctor UAT, or customer-release approval. Existing original DB hold #31, #45 independent security review, #123/#125 authorization revocation reviews and all nine R1-09 NO-GO gates remain. No automatic merge, deployment or real-patient use.

R1-PILOT-05's independently accepted two-JVM, same-disposable-DB restart remains separate evidence. Pilot-06's browser test uses one disposable Spring JVM; it does not redo a two-process restart.

## Pilot-06 first local failure and revised Vite readiness

Owner-reported run at `8e60aeb00c1c0120ca002d654d902f6f7ee68ed7` returned `Tests run: 1, Failures: 1`, stopped before Chromium and failed `[Loopback Vite never became ready]` after the original 50x2s HTTP poll. The exact Vite startup reason is **not proven** without `backend/target/pilot06-vite-test.log`. This branch now switches to the prebuilt Vite Preview server, explicitly configures its loopback API proxy, refuses any open listener on port 5176, verifies the expected built HTML, uses a bounded polling loop, and reports the Vite log path on failure. The new source SHA requires a fresh Windows retest; do not infer PASS from the corrected build alone.

If retest fails, inspect the Vite startup log in the *new isolated worktree*:
```powershell
Get-Content .\\backend\\target\\pilot06-vite-test.log -Tail 80
```

Never run another Vite preview bound to this port or point the proxy at a customer backend to make the test pass.

## Pilot-06 second local attempt and targeted Windows probe correction

The owner ran the corrected Vite Preview SHA `4598db6854988bbde4e5407febb69b303cb08878` on Windows. The JUnit probe still FAILED before Chromium: `PILOT06 Vite preview not ready: HttpTimeoutException` (`1 test, 1 failure`). The actual Vite log showed only the startup announcement:

```
Local: http://127.0.0.1:5176/
```

This **confirms Preview announced a loopback listener, but does not prove that an HTTP GET was successfully served**. The JDK `HttpClient` timeout may reflect JVM loopback client behavior or a stalled preview response. It does not establish a Vite build failure, browser failure or clinical API failure.

Correction in same Draft PR #131: `frontend/test/browser/pilot06-preview-readiness.mjs` uses Node's built-in `http.get` with an explicit 127.0.0.1 address, disabled connection pooling, a bounded 2-second per-attempt response timer, a maximum of 12 attempts, a 32 KiB HTML cap, and a strict HTTP 200 + `text/html` + expected title check. The JUnit harness runs that probe as a separate child process, bounds its runtime, validates `PILOT06_NODE_PREVIEW_READY`, and still checks Vite is alive. A failed probe emits only a safe category (`connection-error`, `read-timeout`, or `http-N-or-unexpected-html`), without echoing response bodies or credentials.

**The new SHA is UNTESTED.** It still requires full actual Chromium, authorized Spring Boot API and disposable PG17 acceptance. No user-visible standalone `Write-Host "PILOT-06 LOCAL QA PASSED"` entered after a thrown failure is valid evidence; only a green result from the guarded runner with successful Surefire 1/1 XML is accepted.
