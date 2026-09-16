# Proposed architecture

All components below are planned.

## Runtime shape

The future React browser client calls the Spring Boot REST API. The backend owns authorization, invoice state, payments, banking, journals, and reconciliation. PostgreSQL stores authoritative business data and Flyway manages its schema. Provider calls use Stripe test/sandbox and Plaid Sandbox only.

Later, an outbox publisher sends committed events to Kafka for asynchronous work. Consumers remain within the monolith initially. Redis is introduced for a specific disposable-cache or rate-limit need. A Python/FastAPI analytics service can score synthetic transactions; the backend decides whether to queue a review. Analytics never writes to the ledger. Observability and AWS infrastructure arrive after functional milestones.

## Domain responsibilities

| Domain | Owns |
| --- | --- |
| identity | User credentials, login, token issuance and validation |
| organization | Organizations, memberships, OWNER/EMPLOYEE/ACCOUNTANT roles |
| customer | Organization-scoped customer records |
| invoicing | Invoice lines, calculations, lifecycle transitions |
| payment | Payment/refund attempts, provider references, payment event deduplication |
| banking | Sandbox connections, secured tokens, synchronization cursors and transactions |
| ledger | Accounts, posted journals, entries, reversals, derived balances |
| reconciliation | Matching rules, confidence, review queue and decision history |
| notification | Delivery attempts and retryable notification work |
| audit | Actor, action, timestamp and correlation records without secrets |
| shared | Small technical primitives such as money representation and error metadata |

Organization membership is enforced at application entry points and data access. Repositories never return another tenant's data based on an unscoped identifier. Audit history describes actions; journal entries remain the financial source of truth.

## Transaction and integration boundaries

An invoice operation can update invoice state and post its corresponding journal within one PostgreSQL transaction by calling the ledger API. External provider calls cannot participate in that database transaction. Payment workflows therefore need persisted attempts, provider idempotency keys, explicit states, and verified webhook confirmation. Duplicate events become no-ops; delayed events must not blindly regress state. Specific state machines and ordering rules will be designed in Milestone 5.

The outbox later solves the database/Kafka dual-write gap: commit business state and the event together, publish afterward, and retry safely. An event is a committed fact, not a replacement for a ledger transaction.

## Proposed repository structure

Only planning documents exist now. Create future directories when their milestone starts.

```text
ledgerflow/
  README.md
  PROJECT_STATUS.md
  DECISIONS.md
  docs/
    ARCHITECTURE.md
    LOCAL_DEVELOPMENT.md
    MILESTONES.md
    api/                     # later API conventions/contracts
    events/                  # later versioned event schemas
    runbooks/                # later operations procedures
  backend/                   # milestone 1
    pom.xml
    mvnw, mvnw.cmd, .mvn/
    src/main/java/com/ledgerflow/
      LedgerFlowApplication.java
      identity/, organization/, customer/, invoicing/
      payment/, banking/, ledger/, reconciliation/
      notification/, audit/, shared/
    src/main/resources/
      application.yml
      application-local.yml
      db/migration/
    src/test/java/com/ledgerflow/  # mirrors domain structure
  compose.yaml               # PostgreSQL only initially
  .env.example               # names and safe placeholders, no secrets
  .gitignore
  frontend/                  # milestone 9
  analytics/                 # milestone 10
  observability/             # milestone 11
  infrastructure/terraform/  # milestone 12
  .github/workflows/         # milestone 11 full CI/CD
```

Within each domain, use `api`, `application`, `domain`, and `infrastructure` subpackages when actual complexity needs them. Do not create empty layers or generic base repositories. Domain-level architecture checks should enforce access boundaries as the backend grows.

## Meaningful decisions to revisit

- Identity token lifetime, signing key management, and browser token storage in Milestone 2.
- Supported currencies, rounding policy, invoice lifecycle, and posting points in Milestones 3–4.
- Ledger constraints, locking strategy, reversal semantics, and race handling in Milestone 4.
- Reconciliation rules, tolerances, and explanation of confidence scores in Milestone 7.
- Event partition keys, ordering, compatibility, and retry budgets in Milestone 8.
- Whether measurements justify service extraction or Redis beyond rate limiting.

Each milestone starts with a proposed change list and ends with verified acceptance gates, updated documentation, interview questions, and suggested branch/commit names.
