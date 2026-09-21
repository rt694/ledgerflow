# Architecture sketch

The backend foundation, business records, ledger, local Stripe/Plaid flows, payout allocation, and invoice reconciliation rules are in place. The account-backed sandbox checks and payout-to-bank confirmation are pending. The frontend and background processing below are still planned.

## Runtime shape

The future React browser client calls the Spring Boot REST API. The backend owns authorization, invoice state, payments, banking, journals, and reconciliation. PostgreSQL stores authoritative business data and Flyway manages its schema. Provider calls use Stripe test/sandbox and Plaid Sandbox only.

Later, an outbox publisher sends committed events to Kafka for asynchronous work. Consumers remain within the monolith initially. Redis is introduced for a specific disposable-cache or rate-limit need. A Python/FastAPI analytics service can score synthetic transactions; the backend decides whether to queue a review. Analytics never writes to the ledger. Observability and AWS infrastructure arrive once the main workflows are working.

## Domain responsibilities

| Domain | Owns |
| --- | --- |
| identity | User credentials, login, token issuance and validation |
| organization | Organizations, memberships, OWNER/EMPLOYEE/ACCOUNTANT roles |
| customer | Organization-scoped customer records |
| invoicing | Invoice lines, calculations, lifecycle transitions |
| payment | Payment/refund attempts, provider references, payout allocations, and payment event deduplication |
| banking | Sandbox connections, secured tokens, synchronization cursors and transactions |
| ledger | Accounts, posted journals, entries, reversals, derived balances |
| reconciliation | Matching rules, confidence, review queue and decision history |
| notification | Delivery attempts and retryable notification work |
| audit | Actor, action, timestamp and correlation records without secrets |
| shared | Small technical primitives such as money representation and error metadata |

Organization membership is enforced at application entry points and data access. Repositories never return another tenant's data based on an unscoped identifier. Audit history describes actions; journal entries remain the financial source of truth.

## Transaction and integration boundaries

An invoice operation updates invoice state and posts its corresponding journal within one PostgreSQL transaction by calling the ledger API. `InvoiceLedger` requires an existing transaction; it never commits separately. Ledger-owned JDBC statements share the JPA transaction connection. Immutable headers and entries are assembled together; a deferred PostgreSQL constraint trigger checks balance at commit. Entry insertion locks the header and requires its creating transaction, blocking later appends. External provider calls cannot participate in that database transaction. Payment workflows therefore need persisted attempts, provider idempotency keys, explicit states, and verified webhook confirmation. Duplicate events become no-ops; delayed events must not blindly regress state. Payment reservations, canonical signed-webhook handling, deduplication, invoice settlement, and payout import now implement this boundary. Payout provider calls happen before the short allocation transaction. Automated recovery and payout-to-bank confirmation remain planned.

The outbox later solves the database/Kafka dual-write gap: commit business state and the event together, publish afterward, and retry safely. An event is a committed fact, not a replacement for a ledger transaction.

## Proposed repository structure

The backend includes identity, organization, customer, invoicing, ledger, payment, banking, reconciliation, and shared packages. The other domain packages/services below are planned.

```text
ledgerflow/
  README.md
  PROJECT_STATUS.md
  DECISIONS.md
  docs/
    ARCHITECTURE.md
    LOCAL_DEVELOPMENT.md
    API_WALKTHROUGH.md
    ROADMAP.md
    api/                     # later API conventions/contracts
    events/                  # later versioned event schemas
    runbooks/                # later operations procedures
  backend/                   # backend setup
    pom.xml
    mvnw, mvnw.cmd, .mvn/
    src/main/java/com/ledgerflow/
      LedgerFlowApplication.java
      identity/, organization/, customer/, invoicing/, ledger/
      payment/, banking/, reconciliation/
      notification/, audit/, shared/
    src/main/resources/
      application.yml
      application-local.yml
      db/migration/
    src/test/java/com/ledgerflow/  # mirrors domain structure
  compose.yaml               # PostgreSQL only initially
  .env.example               # names and safe placeholders, no secrets
  .gitignore
  frontend/                  # frontend
  analytics/                 # later analytics service
  observability/             # later monitoring
  infrastructure/terraform/  # optional AWS deployment
  scripts/                   # local key generation and HTTP smoke workflow
  .github/workflows/         # later CI/CD
```

Within each domain, use `api`, `application`, `domain`, and `infrastructure` subpackages when actual complexity needs them. Do not create empty layers or generic base repositories. Domain-level architecture checks should enforce access boundaries as the backend grows.

## Things I still need to work out

- Browser token storage, refresh/logout, and signing key rotation when adding the frontend or tightening security.
- More currencies, additional payment attempts, payout reversals, non-charge payout activity, and credit notes. USD, per-line tax rounding, and DRAFT/ISSUED/PAID/VOID are implemented.
- Manual journals, a configurable chart of accounts, accounting periods, and replayable idempotency contracts as workflows expand.
- Payout-to-bank confirmation, reconciliation tolerances, and remittance references beyond invoice numbers.
- Event partition keys, ordering, compatibility, and retry budgets when adding events.
- Whether measurements justify service extraction or Redis beyond rate limiting.

I'll build this a piece at a time, check that it works, and keep these notes up to date.
