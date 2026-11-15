# Issue #146: alternate Reception preview port proposal

Date: 2026-11-15. Classification: NOT_READY. Inspection only; approval requested before changing the acceptance contract.

## Findings and current evidence

- Inspected existing isolated worktree AroNexa-WIN11P-146-Persistent on codex/issue146-persistent-windows-e2e, HEAD 1abb41ba76acef072001336d8b86d4a0d29ac6a2. Read its AGENTS.md. Tracked source remains unchanged.
- The PowerShell runner scripts/pilot/Test-AroNexa-Pilot05-DistinctJvm-Restart.ps1 exposes ExpectedCommit, Rc03 and QaCentralPath, but no preview port parameter. ARONEXA_PILOT06_API_PORT controls the random backend proxy destination, not the browser preview port.
- frontend/test/browser/pilot08-rc03-created-restart.mjs:26 explicitly asserts origin.port equals 5176. This is an acceptance assertion, so changing it requires the approval requested by the owner in this turn.
- frontend/vite.connected-reception.config.ts:17,21 fixes both server and preview at 5176, with localhost and strictPort enabled. /api/v1 proxy uses independently validated backend port.
- frontend/test/browser/pilot06-preview-readiness.mjs:9 fixes the readiness URL at localhost:5176.
- backend/src/test/java/com/argdigital/aronexa/appointment/Pilot05SeparateJvmHttpTests.java:546-580 fixes the RC03 port-availability probe, Vite CLI port and browser fixture origin at 5176. PILOT09 has its own fixed-port settings and is not part of the proposed RC03 change.
- User-confirmed existing QA listener: node.exe PID 38512 owned by LAPTOP-E4I7MM0Q\ganes on 5176. No termination, process attachment, permissions change or existing QA worktree modification attempted.
- Local availability check: temporary System.Net.Sockets.TcpListener bind to IPAddress.Loopback port 5186 succeeded and was immediately closed in finally. Availability is a snapshot, not a reservation.
- git diff --exit-code succeeded before writing this report. No product source edits, dependency changes, migrations or database access this turn.
- No acceptance rerun performed: the owner explicitly requires approval before changing a hard-coded acceptance contract. No new test PASS claimed.
- Prior exact-source local results, not rerun this turn: frontend 244/244 PASS, 0 failed/skipped; connected TypeScript/Vite build PASS. RC03 backend 1 test, 0 failures, 1 error, 0 skipped; failed safely because port 5176 was occupied. Second JVM not reached. See WIN11P_146_LOCAL_PREFLIGHT_20261115.md.

## Proposed bounded configuration for approval

1. Add an optional -PreviewPort parameter, default 5176, to the existing distinct-JVM runner. Initially allow only 5176 and 5186; reject inherited conflicting settings. Permit the alternate only with -Rc03, so the older Pilot05/PILOT09 contracts remain unchanged.
2. Propagate the selected numeric port through a dedicated test-only setting ARONEXA_PILOT08_RC03_PREVIEW_PORT. Validate in the Java fixture and Node helpers. Do not accept arbitrary origins, hostnames or full URLs.
3. Use that same selected port for Java's port-availability probe, the Vite CLI --port value, Vite server/preview configuration, Node readiness URL and browser fixture origin. Preserve --host 127.0.0.1, strictPort, process cleanup ownership and existing readiness/content checks. Fail if occupied; never fall back to an unrelated listener or silently choose another port.
4. Playwright must assert origin.port equals the independently configured expected port; preserve exact http protocol, 127.0.0.1 hostname and root path assertions. Add rejection of URL credentials, search and fragment. Preserve all registration/search/booking/check-in/queue, duplicate rejection, identity and restart assertions.
5. Keep proxy destination tied to the actual random Spring port. No CORS relaxation, wildcard host, authentication bypass or production profile changes. No broad replacements in other pilot workflows.
6. Test defaults, approved alternate and invalid/conflicting ports. Build connected frontend. Commit only the approved bounded source change, then use the exact resulting clean SHA for the guarded RC03 runner with -PreviewPort 5186 and its freshly created marked synthetic PG17 container. Preserve PID 38512 and the 5176 session. Record frontend/backend counts, distinct process IDs, failures and evidence without credentials.
7. Preserve QA Central #37 and AGENTS.md rule 27. No plan/workflow activation, status manufacture, unrelated-plan substitution or independent approval claim. Local results remain local evidence only. Doctor/Rx/Billing and customer release gates remain pending.

Proposed command after approved implementation (not supported or executed today):

powershell.exe -NoProfile -File scripts/pilot/Test-AroNexa-Pilot05-DistinctJvm-Restart.ps1 -ExpectedCommit <new-clean-exact-SHA> -Rc03 -PreviewPort 5186 -QaCentralPath 'C:/Users/ganes/Application Development/AroNexa-QA-Central'

No merge, deploy, original database access or existing QA session interference.
