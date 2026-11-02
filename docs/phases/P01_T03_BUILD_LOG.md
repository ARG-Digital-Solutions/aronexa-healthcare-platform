# P01-T03 — Organization hierarchy build log

**Status:** IN PROGRESS; verify CI before marking READY FOR REVIEW.
**Branch:** `feature/p01-t03-organization-foundation`.
**Scope:** Tenant, Facility, Branch and Department data records; internal directory persistence; Flyway V2; PostgreSQL integrity tests.

## Planned commands and initial status
| Check | Initial status |
|---|---|
| `mvn -B -f backend/pom.xml verify` with Testcontainers | NOT RUN |
| Fresh Flyway migration V1 → V2 and repeat validation | NOT RUN |
| Valid tenant/facility/branch/department hierarchy | NOT RUN |
| Cross-tenant branch and department parent rejection | NOT RUN |
| Duplicate code and parent deletion protection | NOT RUN |
| Spring Modulith boundary check | NOT RUN |
| `npm ci --no-audit --no-fund && npm run build` | NOT RUN |
| Documentation, PR scope and tracked secret checks | NOT RUN |

### Exclusions
No identity endpoints, authorization/RLS, clinical schema, patient data, customer migrations, deployment or P01-T04. The schema provides relationship integrity only; it does not provide runtime tenant isolation or authorization.

### Release classification
INTERNAL_FOUNDATION; NOT READY for customer deployment. Merge requires explicit owner approval.
