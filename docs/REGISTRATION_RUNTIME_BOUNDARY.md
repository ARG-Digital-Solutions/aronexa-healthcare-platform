# Registration HTTP boundary: bounded engineering checkpoint

Classification: **NOT_READY**. This is a default-off main-source adapter, not an operational production registration writer or completed Reception flow.

The owner authorized routine internal implementation and synthetic testing. No workflow, protected environment, module, production migration or clinical endpoint has been activated. Existing PRs remain drafts. AGENTS.md rule 27 and exact-source Central acceptance remain applicable.

## Implemented boundary

`RegistrationHttpController` handles the existing registration POST shape only when `aronexa.patient.registration.reviewed-activation=true`. No configuration in this change sets it. The old `c2-synthetic` and `registration-candidate` profiles cannot also create this controller. With no separately supplied `RegistrationWriteExecutor`, authorized requests return a generic 503 after permission, live-session identity and module checks. There is no main-source writer factory, key loader or provisioning hook.

When a test-only configuration supplies the existing executor, it uses `patientJdbcTemplate` and the patient transaction manager. The existing restricted SQL function independently repeats live session, tenant, branch, role/module, proof, duplicate, idempotency and audit checks. Neither the runtime pool nor browser receives migration-owner privileges or database proof-key SELECT permission.

Admission uses shared helpers with the existing synthetic adapters: an actual-stream 4096-byte cap and bounded per-JVM budgets of 12 attempts per membership and 120 per tenant per minute. Malformed authenticated JSON consumes quota. These are single-instance controls; distributed admission, deployment resource controls and production readiness are not established.

A real HTTP regression exposed a raw-URI filter bypass: encoding a valid route character allowed a 4097-byte chunked body through to the writer. The filter now uses Spring parsed-path matching. Tests cover encoding in the UUID, `api`, `v1`, `branches` and `patient-registrations` segments. This also fixes the shared synthetic filter; the cap was not increased.

## Reproduction and evidence

Use JDK 21, Maven and Docker with Linux containers. Run only against fresh fictional Testcontainers PostgreSQL 17 databases, with GitHub/deployment credentials and inherited runtime overrides removed. Raw Maven/JUnit output stays private: it may include fictional identities, ephemeral infrastructure metadata and test credentials.

```text
mvn -B -f backend/pom.xml clean -Dtest=RegistrationRuntimeHttpAcceptanceTests,RegistrationRuntimeBoundaryTests,SyntheticRegistrationAttemptLimiterTests test
mvn -B -f backend/pom.xml -Pwin11p-discovery -Dwin11p.run.id=<unique> -Dwin11p.source.sha=<exact-commit> test
mvn -B -f backend/pom.xml -Pwin11p-ordinary -Dwin11p.run.id=<unique> test
```

The ordinary profile explicitly excludes the eight guarded Windows/operator/phase methods. Do not use unfiltered Maven as a substitute for their controlled acceptance instructions. Do not enable runtime flags or synthetic profiles on a persistent installation to reproduce these tests.

Observed before the implementation commit:

| Check | Actual result |
| --- | --- |
| New main-handler test before wiring | Expected RED: HTTP 403 instead of 201 |
| Missing-writer bean/HTTP checks before wiring | Expected RED: two failures; no main controller |
| Encoded-route overflow before repair | Expected RED: HTTP 201 instead of 413 |
| Clean focused verification after repair | 22 passed, zero failures/errors/skips; Maven exit 0; 35.493 seconds |
| Focused breakdown | 13 real HTTP/PG17, 4 bean/adaptor, 5 existing admission tests |
| Focused lifecycle diagnostics | No Hikari closed-connection warning or Surefire shutdown error; both new test pools completed shutdown |
| Review | Separate AI code review; no further material source finding; no independent human signoff |

Normal Mockito/JDK agent diagnostics and existing test compiler/development-security warnings were retained. No timeout or diagnostic was suppressed. The new HTTP suite closes its Spring context before its disposable database; this does not establish a repair of the previously observed full-suite shutdown problem. Exact-commit ordinary-suite counts, diagnostics, inventory comparison and evidence digests are recorded in the draft PR and QA issue #38 after reproduction.

The existing 352 test-method identities are preserved. Seventeen new methods make the expected discovered inventory 369: 360 ordinary, one discovery and eight guarded. This source is outside the existing reviewed WIN11P source/changed-path contract. Do not run or relabel the old plan as acceptance of this revision. A review proposal may record the new paths, blobs and methods, but cannot activate a plan or enlarge an execution allowlist.

## Decision record and remaining owners

| Disposition | Decision / owner |
| --- | --- |
| Approved engineering | Owner's standing authorization covers this disabled adapter, bounded admission correction and fictional testing. AI review is recorded as AI review. |
| Held / not performed | Production writer factory, protected installation key storage, runtime activation, production V10 installation, original-database access, workflow activation, merge and deployment. |
| Pending migration and security review | Qualified reviewer must assess immutable V10 checksum/approval, NOLOGIN writer privileges, transactional installer rehearsal, least-privilege runtime and proof-domain/key binding. Current read-only installer preflight needs privileged key-store access and is not a runtime startup gate. |
| Pending key/recovery engineering | Protected per-installation key loading, rotation, interruption/recovery, key escrow and genuine encrypted backup/restore; unencrypted restore is not substitute evidence. |
| Pending Central acceptance | QA maintainer reviews a new exact-source scope/inventory proposal and applicable governance decision. No old allowlist is promoted and no stale PASS is reused. |
| Pending administrator controls | Administrator verifies three protected WIN11P environments, ephemeral Windows runner and least-privilege credentials. Earlier API 403 responses remain unknown configuration state. |
| Pending independent acceptance | No separate qualified human is identified: the two known GitHub usernames belong to the same owner. A genuine reviewer/operator must supply required exact-revision dispositions and Windows evidence. |

All eight guarded methods remain **NOT_RUN**, overall acceptance **NOT_PASS**, and WIN-11B **NOT_READY**. A running browser test lab, local test success or owner/AI review cannot replace those gates.
