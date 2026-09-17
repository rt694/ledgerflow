# Progress

Updated: 2026-09-17.

## Done so far

- Public GitHub repository, architecture notes, and local setup.
- Java 21/Spring Boot backend with Maven Wrapper, PostgreSQL Compose, Flyway, health checks, validation, and Swagger UI.
- Registration/login, BCrypt password hashing, signed 15-minute JWTs, and consistent security errors.
- Organizations and OWNER/EMPLOYEE/ACCOUNTANT memberships, with current database roles controlling access.
- Owner-only membership changes and last-owner protection under an organization lock.
- Organization-scoped customer creation, reads, updates, and paginated lists.
- USD invoices and line items, decimal totals, per-line HALF_UP tax rounding, and bounded inputs.
- Draft editing, issuing, voiding, fixed issued content, and customer snapshots.
- Scoped row locks and monotonic versions to reject stale customer/invoice writes.
- Database constraints for cross-organization references, unique invoice numbers, line calculations, and valid states.
- Double-entry USD accounts, invoice postings, exact reversals, and derived balances.
- Commit-time balancing and database guards against editing, deleting, or extending posted journals.
- Local key generation and a repeatable synthetic HTTP smoke workflow.

## What I've checked

- `./mvnw verify` passed: formatting, compilation, JAR packaging, 19 focused tests, and 51 PostgreSQL integration tests. No skips.
- Real signed-token tests reject tampering, expired tokens, wrong issuers/audiences, missing required claims, future issue times, and invalid subjects.
- Negative tests cover cross-organization reads/writes, wrong roles, membership removal, and ignored JWT role claims.
- Invoice tests cover rounding, transitions, fixed snapshots, stale versions, duplicate numbers, and rollback after a database conflict.
- Concurrent requests preserve one invoice-edit winner and at least one organization owner.
- Flyway applies/validates all four migrations; a fresh connection finds the same migration history.
- Started the packaged app against the local Compose database and ran the synthetic workflow over HTTP.
- Checked live health/OpenAPI and bearer authentication in Swagger, documentation links, and exclusions for credentials/build output.

Ledger tests also cover concurrent issuance, posting rollback, empty/unbalanced journals, incorrect reversals, tenant boundaries, and upgrading existing invoice history.

No real financial data, provider calls, or performance measurements have been used.

## Up next

Stripe Sandbox payments: test-only payment intents, verified webhooks, duplicate-event handling, payment postings, and PAID invoice states. The ledger currently supports invoice issue/void workflows; there is no manual journal-write endpoint.

The other ideas are in the [build checklist](docs/ROADMAP.md). The [API walkthrough](docs/API_WALKTHROUGH.md) explains what works now.

## Local environment

- Java 21.0.12.1 for this backend; global Java still defaults to 17.
- Maven 3.9.16 through the wrapper; PostgreSQL 17.10 through Docker.
- API port 18080, database port 55432, both on localhost.
- JWT signing keys live in ignored `.local/`; tests generate temporary keys.
- Python 3 is optional for the smoke workflow. Node isn't needed until the frontend.
