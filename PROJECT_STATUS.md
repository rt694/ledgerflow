# Progress

Updated: 2026-09-21.

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
- Plaid Sandbox Link tokens, idempotent public-token exchange, encrypted access tokens, and role-scoped bank reads.
- Cursor-based account and transaction sync with add/change/remove history and verified Plaid webhooks.
- Reconciliation cases, explainable exact and referenced partial-payment candidates, reviewed decisions, and bank-payment journals.
- Verified Stripe payout allocations with exact gross, fee, and net amounts; mixed-organization batches are rejected.
- Immutable payout journals move gross clearing into processing fees and payouts in transit.
- Reviewed payout deposits move the exact net amount from payouts in transit into cash.

## What I've checked

- `./mvnw verify` passed: formatting, compilation, JAR packaging, 31 focused tests, and 83 PostgreSQL integration tests. No skips.
- Real signed-token tests reject tampering, expired tokens, wrong issuers/audiences, missing required claims, future issue times, and invalid subjects.
- Negative tests cover cross-organization reads/writes, wrong roles, membership removal, and ignored JWT role claims.
- Invoice tests cover rounding, transitions, fixed snapshots, stale versions, duplicate numbers, and rollback after a database conflict.
- Concurrent requests preserve one invoice-edit winner and at least one organization owner.
- Flyway applies/validates all ten migrations; a fresh connection finds the same migration history.
- Started the packaged app against the local Compose database and ran the synthetic workflow over HTTP.
- Checked live health/OpenAPI and bearer authentication in Swagger, documentation links, and exclusions for credentials/build output.

Ledger tests also cover concurrent issuance, posting rollback, empty/unbalanced journals, incorrect reversals, tenant boundaries, and upgrading existing invoice history.

Stripe SDK contract checks use a local HTTP fixture server; payment integration tests use a mocked provider and real PostgreSQL. They cover raw signatures, test-mode guards, retries, concurrent creation, delayed/duplicate events, refunds, disputes, cancellation, payout itemization, exact fees, tenant ownership, and atomic rollback.

Plaid tests use synthetic provider responses with real PostgreSQL. They cover authenticated token encryption, idempotent exchange, role and tenant boundaries, cursor restarts, account balances, transaction adds/changes/removals, append-only history, and ES256 webhook body verification.

Reconciliation tests cover exact amount/reference scoring, referenced partial payments, Stripe payout deposits, visible ambiguity, pending and outgoing exclusions, overpayment rejection, idempotent refresh, explicit and concurrent review, stale decisions, role and tenant boundaries, balanced cash postings, and append-only decision history.

No real financial data, account-backed Stripe or Plaid calls, or performance measurements have been used.

## In progress

Stripe Sandbox code is implemented: intent reservations and stable provider keys, signed webhooks, PAID invoice settlement, payment/refund/dispute journals, cancellation, one full-refund request per payment, and manual import of paid provider payouts. Payout import accepts only charge batches that resolve completely to one LedgerFlow organization, records Stripe fees, and moves the net into payouts in transit. Plaid Sandbox code connects fake banks and synchronizes account and transaction changes. Reconciliation explains empty, single, and ambiguous invoice or payout candidate sets. Reviewed invoice receipts move receivables into CASH, while reviewed payout deposits move payouts in transit into CASH without changing imported provider history.

The account-backed smoke checks are pending because Stripe and Plaid credentials are not configured locally. Follow [payment setup](docs/STRIPE_SANDBOX.md) and [fake bank setup](docs/PLAID_SANDBOX.md). Both providers default to disabled; no mock provider is used in the running app.

## Up next

Finish both account-backed Sandbox checks, then start the first frontend workflow around login, invoices, payments, and review. Automated provider-request recovery, payout reversals, non-charge payout activity, credit notes, and additional payment attempts are not implemented.

The other ideas are in the [build checklist](docs/ROADMAP.md). The [API walkthrough](docs/API_WALKTHROUGH.md) explains what works now.

## Local environment

- Java 21.0.12.1 for this backend; global Java still defaults to 17.
- Maven 3.9.16 through the wrapper; PostgreSQL 17.10 through Docker.
- API port 18080, database port 55432, both on localhost.
- JWT signing keys live in ignored `.local/`; tests generate temporary keys.
- Stripe CLI 1.51.0 installed user-locally; account authentication is pending.
- Python 3 is optional for the smoke workflow. Node isn't needed until the frontend.
