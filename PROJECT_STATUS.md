# Progress

Updated: 2026-09-16.

## Done so far

- Checked the local development tools and confirmed Docker is running.
- Created the public GitHub repository and a separate local checkout.
- Sketched the architecture, folder layout, and build order.
- Wrote setup notes and checked the documentation links.

## Up next

- Install/select an ARM64 JDK 21; the active JDK is currently Java 17.
- Set up Spring Boot with Maven Wrapper and pin compatible stable dependencies.
- Add PostgreSQL through Docker Compose and manage the schema with Flyway.
- Add health checks, validation, API docs, and the first tests.

The app hasn't been built yet. The rest of the ideas are in the [build checklist](docs/ROADMAP.md).

## Environment notes

From the initial local check:

- macOS ARM64; Java/javac 17.0.20.1.
- Node.js 24.20.0; npm 11.19.0; Git 2.39.3.
- Docker client and daemon 29.7.2; Compose v5.5.0.
- Python 3.12.14.
- Maven, Gradle, PostgreSQL command-line tools, and Terraform weren't on PATH.

No application builds, tests, sandbox calls, or performance measurements have been run yet.
