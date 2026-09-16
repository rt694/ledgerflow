# Milestone plan

Implement one milestone at a time. Do not start the next implementation milestone automatically. Each milestone begins with repository inspection and a reviewable file-change proposal, then proceeds through small changes and its acceptance gate. Update README, PROJECT_STATUS, and DECISIONS with actual results, manual verification, and unresolved risks. Supply several interview questions and branch/commit suggestions at completion.

Calendar estimates would be premature before measuring the first implementation milestone. The gates below define progress by verified behavior instead of dates.

| Milestone | Scope | Acceptance gate |
| --- | --- | --- |
| 0 — Planning | Environment, architecture, structure, commands and decisions | Tool findings recorded; planning documents and links checked; no application implementation |
| 1 — Backend foundation | Spring Boot/Java 21/Maven Wrapper, profiles, PostgreSQL Compose, Flyway, validation, consistent errors, Actuator, OpenAPI | Formatting, compilation, unit and PostgreSQL integration tests pass; real database startup and migrations verified; documented health/API checks succeed |
| 2 — Identity and organizations | Registration/login, password hashing, JWT, memberships and roles | Authorized requests succeed; expired/invalid tokens, prohibited roles, and cross-tenant access fail; secrets stay outside code and logs |
| 3 — Customers and invoices | Tenant-scoped customers, invoice lines, calculations and lifecycle | API/service/repository/integration tests cover rounding and invalid transitions; foreign-tenant associations rejected |
| 4 — Ledger | Accounts, journals, balanced postings, immutability, reversals and balances | Currency, rounding, duplicate posting, database constraints, rollback, and concurrent requests tested; posted entries cannot be altered or deleted |
| 5 — Stripe Sandbox | Test-only payment intents, signed webhooks, success/failure/refund/dispute handling | Provider-contract fixtures plus sandbox smoke checks; invalid signatures rejected; duplicate/delayed/out-of-order events and retries produce correct ledger effects |
| 6 — Plaid Sandbox | Simulated connections, secured tokens, account/balance/transaction sync and webhooks | Sandbox smoke checks and fixture-based contract tests; cursor pagination, duplicate data, updates/removals, and sync recovery verified |
| 7 — Reconciliation | Deterministic matching, confidence, manual review and history | Known synthetic matches reconcile; ambiguous cases queue for review; decisions remain auditable and cannot directly mutate posted journals |
| 8 — Events | Transactional outbox, Kafka, event versions, idempotent consumers, backoff, dead letters and correlation | Failure injection proves outbox recovery, duplicate safety, retries and dead-letter routing; ordering and schema compatibility documented |
| 9 — Frontend | React/TypeScript authentication, invoices, payments, banking, review, ledger and dashboards | Type/lint/build checks and end-to-end synthetic workflow pass; responsive accessibility and loading/empty/error states checked; no frontend secrets |
| 10 — Analytics | Python/FastAPI explainable anomaly rules or small model | pytest and API contract checks pass; flags include reasons; service failures do not corrupt financial operations; no ledger write authority |
| 11 — Production readiness | Tracing, metrics, dashboards, rate limits, security headers, full CI/CD, dependency/secret scanning | Trace/correlation propagation demonstrated; documented failure dashboards work; CI gates pass; repeatable performance/recovery tests yield recorded measurements |
| 12 — AWS | Terraform, cost estimate, deployment/rollback/monitoring/teardown | Explicit approval before paid provisioning; local gates pass first; infrastructure validation, approved deployment smoke checks and documented teardown complete |

## Sequencing considerations

- Payment and banking milestones need durable provider deduplication before Kafka arrives. Kafka extends reliable processing; it does not introduce financial correctness for the first time.
- Invoice posting points are designed with the ledger milestone. Earlier invoices must not be described as already ledger-backed.
- Use JUnit 5, Mockito where isolation helps, and Testcontainers with PostgreSQL for database behavior. Add fixture-based provider contracts at integration boundaries and browser end-to-end tests when the frontend exists.
- Keep synthetic datasets versioned and describe assumptions. Reconciliation accuracy requires known expected matches; throughput/latency tests require documented workloads and environments.
- Full observability and CI/CD arrive in Milestone 11, but baseline health checks, secure configuration, and tests begin in Milestone 1. Redis is added only when a concrete requirement justifies it.
- AWS service choices and costs remain undecided until the deployment milestone. No resource provisioning is authorized by this plan.
