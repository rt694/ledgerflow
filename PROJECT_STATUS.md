# Progress

Updated: 2026-09-16.

## Done so far

- Created the public GitHub repository and sketched the architecture and build order.
- Installed Temurin Java 21 in the local user tools directory without changing the global Java default.
- Added a Spring Boot 3.5.16 backend and Maven 3.9.16 Wrapper with a pinned download checksum.
- Added PostgreSQL 17.10 through Docker Compose, with localhost access and an ignored local password file.
- Added Flyway's first migration to create the application schema. No business tables yet.
- Configured JPA schema validation, disabled Open Session in View, and separated local/test settings.
- Added health checks, local Swagger UI/OpenAPI, request validation, and safe Problem Details responses.
- Added a temporary greeting endpoint that doesn't save data.
- Added required formatting checks and focused/integration tests.

## What I've checked

- `./mvnw verify` passed: formatting, compilation, JAR packaging, 9 focused tests, and 5 PostgreSQL integration tests. No tests skipped.
- Flyway applied the migration, validated its checksum, and did nothing on a second migration run.
- Started the packaged app with the local profile against the Compose database.
- Verified HTTP 200 for health, OpenAPI, Swagger UI, and a valid greeting; verified HTTP 400 with field errors for a blank name.
- Confirmed the development database contains a successful Flyway migration record.
- Added a fresh-connection regression test for PostgreSQL search-path behavior and verified repeated startup.
- Verified that the default profile keeps OpenAPI and Swagger UI disabled while health remains available.
- Checked documentation links and Git exclusions for local credentials/build output.

No payment integrations, financial workflows, or performance measurements have been run.

## Up next

Users and organizations: registration/login, password hashing, JWTs, roles, and organization-scoped data access. Authentication isn't implemented yet. The other ideas are in the [build checklist](docs/ROADMAP.md).

## Local environment

- Java 21.0.12.1 is available for this backend; the global Java default remains 17.
- Node.js 24.20.0; npm 11.19.0; Git 2.39.3.
- Docker client and daemon 29.7.2; Compose v5.5.0.
- Python 3.12.14.
- Maven runs through the wrapper; database command-line tools run inside the PostgreSQL container.
- Local ports: API 18080, PostgreSQL 55432. Another project already occupies 8080/5432.
