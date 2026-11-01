# P01-T01 scaffold build log
Scope: Java 21/Spring Boot/Spring Modulith/React/TypeScript/Vite baseline only.
No patient/clinical/auth/DB modules, Flyway migrations, deployments or tenant data included.

## Build and review plan
- Backend: `mvn -B -f backend/pom.xml verify` (requires Maven and network/dependency cache).
- Frontend: `cd frontend && npm install && npm run build` (lockfile generation requires npm registry access; lockfile to be retained before merge).
- Architecture: `ApplicationModules.verify()` test included.
- CI: existing PR quality workflow must be hardened to run scaffold builds even if only one component is present.

## Status
All build / tests: NOT RUN until actual CI or local execution evidence is captured.
Security integration / PostgreSQL / Flyway / clinical regression: NOT APPLICABLE to this scaffold task; planned later.
Do not merge until backend/frontend build evidence and reproducible frontend lockfile are verified.
