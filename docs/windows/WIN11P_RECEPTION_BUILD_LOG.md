# WIN-11P Reception integration handoff — 2026-11-15

1. **Classification:** NOT_READY. Requested completion condition not met.
2. **Authorized scope:** fictional Reception login/search/register/book/check-in/queue, security negatives, restart and Windows QA launch; no MVP scope changes.
3. **Inspected baseline:** `112f106131499a1c47f20bdb8c1f66ab2353b4d3`; isolated branch `codex/win11p-integrated-reception-e2e`.
4. **Changes:** Windows orchestration wrapper, pre-database portable JVM identity guard and two unit tests, RC03 browser search-before-create and MRN retrieval-after-create checks, Windows runbook and this evidence record.
5. **Architecture:** reuse guarded test-only Spring adapters and connected React. Normal packaged backend remains unchanged; no new production API activation.
6. **Database:** no database accessed. Owner-provided Windows PG17.11 baseline is not independently reverified. No original/demo/production/existing-pilot database modified. New harness uses fresh labelled PG17 containers only.
7. **Migrations:** V1–V9 untouched; review-only registration/search and appointment proof SQL remain in existing disposable-test gates.
8. **Frontend QA:** `npm ci --no-audit --no-fund`; `npm test`: 244 tests, 244 pass, zero failures/skips. `npm run build:connected:test`: TypeScript and Vite PASS. These results are model/build evidence, not browser E2E.
9. **Java QA:** full JDK21 compilation including test sources PASS as part of `mvn -Dtest=Win11pPortableRuntimeGuardTests test`; 2 tests, zero failures/errors/skips. Test-first run failed compilation on the missing guard; after implementation the focused suite passed. Initial workspace runtime lacked usable JDK compilation; downloaded full Temurin JDK21 and used workspace trust store/proxy for Maven. No repository dependency versions changed.
10. **Full Maven:** `mvn -B -f backend/pom.xml verify`: FAILED, 101 reported tests, 0 assertion failures, 41 errors, 0 skips. Container suites cannot provision Docker here; Pilot05 has no authorized run marker/environment; Mockito inline mock maker cannot attach in this sandbox. These errors and prior full-QA failures remain OPEN. Initialization errors can collapse a class to one reported test; 101 is not the intended complete suite coverage.
11. **Browser and restart:** updated RC03 script passes `node --check`; Chromium/HTTP/PG17 journey, different-PID persistence and security negatives NOT EXECUTED in this environment. No screenshot or restart PASS manufactured.
12. **Windows:** wrapper not executed on Windows; PowerShell parser/runtime acceptance pending. Build-JDK regression is separated from portable-runtime RC03 and operator JVMs, because the jlink runtime lacks full JDK tooling used by mocked unit suites.
13. **Central QA:** baseline combined statuses returned empty. Draft plan `WIN11P_RECEPTION_E2E` registered separately; workflow/allowlist activation and independent disposition pending. No status PASS or dispatch performed.
14. **Review:** AI code review of bounded changes only; not independent security or clinical signoff. Independent security and WIN-14 retain their gates.
15. **Remaining implementation:** normal-JAR integration, named Windows disposable database marker/provisioning contract, operator-record persistence across manual lab launches. Automated RC03 persistence and later operator lab are different databases. Customer release remains NO-GO.
16. **Delivery:** one isolated local source branch; draft PR is not created. Automatic approval review rejected source and QA-plan pushes as unapproved external publication. No automatic merge/deployment. See `WIN11P_RECEPTION_E2E.md` for Windows command and explicit limits. Git commit in the handoff identifies the exact authored source; Windows evidence must be rerun against it.

## Full Maven error inventory

All errors below remain unresolved; no unavailable test is treated as PASS.

| Suite | Reported tests | Errors | Cause category |
|---|---:|---:|---|
| AronexaApplicationTests | 1 | 1 | Docker unavailable |
| AppointmentCandidateHttpAcceptanceTests | 1 | 1 | Docker unavailable |
| AppointmentPersistenceProofTests | 1 | 1 | Docker unavailable |
| Pilot05SeparateJvmHttpTests | 1 | 1 | Missing guarded disposable run settings |
| AuthRoleBoundaryTests | 1 | 1 | Docker unavailable |
| AuthenticationIntegrationTests | 1 | 1 | Docker unavailable |
| OrganizationAccessIntegrationTests | 1 | 1 | Docker unavailable |
| PostgresTenantRlsTests | 1 | 1 | Docker unavailable |
| RealHttpDenialStatusTests | 1 | 1 | Docker unavailable |
| EncounterAuthenticatedPersistenceProofTests | 1 | 1 | Docker unavailable |
| EncounterDraftPostgresProofTests | 1 | 1 | Docker unavailable |
| ModuleAccessServiceTests | 5 | 5 | Mockito attachment unavailable |
| ModuleRegistryPostgresTests | 1 | 1 | Docker unavailable |
| OpdAppointmentSchemaCandidateTests | 1 | 1 | Docker unavailable |
| OpdBookingCommandTests | 7 | 7 | Mockito attachment unavailable |
| OpdIntakeProductionHttpAcceptanceTests | 1 | 1 | Docker unavailable |
| OpdRestrictedBookingPostgresTests | 1 | 1 | Docker unavailable |
| OrganizationPersistenceTests | 1 | 1 | Docker unavailable |
| PatientAccessServiceIntegrationTests | 1 | 1 | Docker unavailable |
| PatientCandidateSearchHttpAcceptanceTests | 1 | 1 | Docker unavailable |
| PatientIdentitySchemaTests | 1 | 1 | Docker unavailable |
| PatientRegistrationReadHttpAcceptanceTests | 1 | 1 | Docker unavailable |
| PatientRuntimeBoundaryTests | 1 | 1 | Docker unavailable |
| RegistrationCandidateHttpAcceptanceTests | 1 | 1 | Docker unavailable |
| RegistrationCandidateSqlAcceptanceTests | 1 | 1 | Docker unavailable |
| RegistrationDatabaseProvisionerTests | 1 | 1 | Docker unavailable |
| RegistrationWritePreflightTests | 1 | 1 | Docker unavailable |
| SyntheticPostgresLogRedactionAcceptanceTests | 1 | 1 | Docker unavailable |
| SyntheticRegistrationFrontendContractTests | 1 | 1 | Docker unavailable |
| SyntheticRegistrationHttpAcceptanceTests | 1 | 1 | Docker unavailable |
| SyntheticRegistrationIntegrationTests | 1 | 1 | Docker unavailable |
