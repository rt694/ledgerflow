# Project status

Last inspected: 2026-09-16.

## Completed

- Milestone 0: inspected the workspace and local tooling.
- Initial inspection found an empty project mirror with no application or Git repository.
- Created a durable Git checkout and prepared the planning files for public GitHub publication.
- Documented the modular-monolith architecture, Maven recommendation, domain boundaries, safety constraints, local commands, and milestone acceptance gates.
- Verified Docker client, Compose, and daemon availability.
- Created foundational planning documents; checked links and consistency.

## Active

- No implementation milestone is active. Milestone 0 is complete.

## Prerequisites for Milestone 1

- Install/select an ARM64 JDK 21 and verify both `java` and `javac` use it.
- Pin compatible stable Spring Boot, Maven, PostgreSQL, and OpenAPI library versions when generating the backend; do not choose snapshots.
- Generate the Maven Wrapper as part of the Spring Boot project.

These prerequisites do not block planning. No software installations, Git initialization, containers, or cloud resources were created during Milestone 0.

## Upcoming

Milestones 1–12 are planned, not implemented. See [acceptance gates](docs/MILESTONES.md).

## Verification evidence

- macOS host reports `arm64`.
- Active Java and javac: Temurin 17.0.20.1; Java 21 unavailable in the inspected locations.
- Node.js 24.20.0; npm 11.19.0; Git 2.39.3.
- Docker client and daemon 29.7.2; Compose v5.5.0.
- Python 3.12.14.
- Maven, Gradle, `psql`, `pg_isready`, and Terraform absent from PATH.
- No application tests, builds, migrations, sandbox calls, or performance measurements have been run.
