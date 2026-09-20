# Build checklist

I'm working through this in small pieces so I can understand and test each part before moving on. The main backend, invoice ledger, Stripe flow, and Plaid banking flow are implemented locally. The two provider-backed checks still need Sandbox credentials.

I'll keep the setup notes, progress, and decisions updated as the code changes. There aren't any delivery dates yet.

| Part | Progress | What I want to build | How I’ll check it |
| --- | --- | --- | --- |
| Planning | Done | Environment, architecture, structure, commands and decisions | Tool findings recorded; planning documents and links checked; no application implementation |
| Backend foundation | Done | Spring Boot/Java 21/Maven Wrapper, profiles, PostgreSQL Compose, Flyway, validation, consistent errors, Actuator, OpenAPI | Formatting, compilation, unit and PostgreSQL integration tests pass; real database startup and migrations verified; documented health/API checks succeed |
| Identity and organizations | Done | Registration/login, password hashing, JWT, memberships and roles | Authorized requests succeed; expired/invalid tokens, prohibited roles, and cross-tenant access fail; secrets stay outside code and logs |
| Customers and invoices | Done | Tenant-scoped customers, invoice lines, calculations and lifecycle | API/service/repository/integration tests cover rounding and invalid transitions; foreign-tenant associations rejected |
| Ledger | Done | Accounts, journals, balanced postings, immutability, reversals and balances | Currency, rounding, duplicate posting, database constraints, rollback, and concurrent requests tested; posted entries cannot be altered or deleted |
| Stripe Sandbox | In progress | Test-only payment intents, signed webhooks, success/failure/refund/dispute handling | Provider-contract fixtures plus sandbox smoke checks; invalid signatures rejected; duplicate/delayed/out-of-order events and retries produce correct ledger effects |
| Plaid Sandbox | In progress | Simulated connections, secured tokens, account/balance/transaction sync and webhooks | Local implementation and PostgreSQL tests pass; account-backed smoke check still needs Sandbox credentials and a public HTTPS callback |
| Reconciliation | In progress | Deterministic matching, confidence, manual review and history | Exact and referenced partial bank payments can be reviewed safely; provider payout allocation still needs work |
| Events | To build | Transactional outbox, Kafka, event versions, idempotent consumers, backoff, dead letters and correlation | Failure injection proves outbox recovery, duplicate safety, retries and dead-letter routing; ordering and schema compatibility documented |
| Frontend | To build | React/TypeScript authentication, invoices, payments, banking, review, ledger and dashboards | Type/lint/build checks and end-to-end synthetic workflow pass; responsive accessibility and loading/empty/error states checked; no frontend secrets |
| Analytics | To build | Python/FastAPI explainable anomaly rules or small model | pytest and API contract checks pass; flags include reasons; service failures do not corrupt financial operations; no ledger write authority |
| Monitoring and reliability | To build | Tracing, metrics, dashboards, rate limits, security headers, full CI/CD, dependency/secret scanning | Trace/correlation propagation demonstrated; documented failure dashboards work; CI checks pass; repeatable performance/recovery tests yield recorded measurements |
| AWS | To build | Terraform, cost estimate, deployment/rollback/monitoring/teardown | Explicit approval before paid provisioning; local checks pass first; infrastructure validation, approved deployment smoke checks and documented teardown complete |

## Things to keep in mind

- Payments and bank syncing need duplicate handling before Kafka is added.
- I'll decide when invoices should create journal entries while building the ledger.
- Database tests should use real PostgreSQL through Testcontainers. Provider tests can use fixtures, with separate sandbox checks.
- Reconciliation tests need synthetic transactions with known expected matches. Performance numbers need repeatable workloads before I can make any claims.
- Health checks and tests start with the backend. Full monitoring and CI/CD come later.
- I'll add Redis only for a concrete use case. AWS choices and costs can wait until the app works locally; this checklist doesn't authorize paid resources.
