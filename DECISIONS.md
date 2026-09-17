# Why I chose this approach

These are my starting choices. I'll update them as I build and learn what works.

## One backend to start

I'm starting with a modular monolith: one Spring Boot app, with packages organized around business domains. One PostgreSQL database makes it possible to update business records and ledger entries in the same transaction. The tradeoff is that everything shares a process and gets released together. I'll consider separate services if I find a concrete need for them.

## Java 21 and Maven

Maven's standard layout and build lifecycle are enough for this project. A pinned Maven Wrapper will make the build tool consistent across machines, though Java still needs to be installed separately. The pinned backend versions are recorded below.

References: [Spring Boot requirements](https://docs.spring.io/spring-boot/system-requirements.html), [Maven Wrapper](https://maven.apache.org/tools/wrapper/).

## Packages by domain

Customers, invoices, payments, banking, and the ledger will each own their code and data access. If one domain needs another, it should call that domain's application API rather than reaching into its repositories. I'll keep shared code small and avoid adding empty layers just to fit a pattern.

## Money and organization boundaries

Money will use Java BigDecimal and PostgreSQL NUMERIC, with an explicit ISO currency code. I'll define currency scale and rounding before implementing calculations. Posted journals must balance within one currency and stay immutable; corrections will use linked reversals or compensating entries.

Database constraints should back up application validation. Balancing multiple rows can't be enforced with a simple CHECK constraint, so I use a deferred constraint trigger to validate each complete journal at commit.

Tenant-owned data will be scoped to an organization. Access must come from authenticated membership, with organization checks in queries and constraints where practical. I'll test cross-organization access attempts. Row-level security is something to revisit once I understand the connection-pool implications.

## Background events later

I'll use local domain calls first. When I add Kafka, an outbox will store events in the same transaction as business changes, then publish them afterward. Delivery can happen more than once, so consumers need idempotency. Provider webhooks need duplicate and ordering safeguards even before Kafka is added.

Redis can help with something like rate limiting or disposable caching later. It won't be the source of truth for balances or payment idempotency.

## Fake data only

Stripe will use test/sandbox credentials and Plaid will use Sandbox. Secrets stay out of Git and logs. AWS is optional: I'll get the app working locally, estimate costs, and get explicit approval before creating paid resources.

## Backend setup choices

I chose Spring Boot 3.5.16 for the requested JUnit 5 stack and familiar Spring MVC/JPA setup. It's a stable 3.5 release; newer Boot major versions are available, so this is a deliberate compatibility choice rather than a claim to use the latest major. springdoc 2.8.17 documents this Boot 3 backend. Maven 3.9.16 and PostgreSQL 17.10 are pinned.

References: [Boot 3.5 requirements](https://docs.spring.io/spring-boot/3.5/system-requirements.html), [springdoc Boot 3 documentation](https://springdoc.org/v2/).

Flyway owns schema changes; Hibernate validates mapped entities. The first migration creates the `ledgerflow` schema; later migrations add identity, organization, customer, invoice, and ledger tables. I disabled Open Session in View to keep future database access in explicit application transactions.

API errors use Spring ProblemDetail, with an HTTP code and a list of field errors for validation. I preserve framework response headers such as Allow, and replace internal error details with safe messages. Authentication now uses the same Problem Details format, including errors returned by the security filter.

The greeting endpoint is a temporary, stateless way to test the foundation. It doesn't belong to a financial domain and doesn't save data. Business code will be added by domain as those features are built.

PostgreSQL integration tests run in Failsafe during `verify` using Testcontainers. They're required and fail without Docker. Spotless checks Java formatting during validation. Local API/database ports are 18080/55432 because another project was already using 8080/5432. Both listen on localhost, and API docs are enabled only in local/test profiles.

Flyway's history is explicitly kept in `public`, while business tables will live in `ledgerflow`. PostgreSQL's default search path can change when a schema matches the login username; leaving history placement implicit caused a second startup to try the first migration again. A fresh-connection regression test now uses the local database username to cover that case.

## Users, organizations, and invoices

I used Spring Security's resource-server JWT support instead of writing a token filter. Local RSA keys are generated once and stay outside Git. Tokens use RS256, a UUID user subject, the expected issuer/audience, and a 15-minute lifetime. The decoder checks the signature, time bounds, and required claims. Refresh tokens, server-side logout, and key rotation are future work.

References: [Spring Security JWT validation](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html), [password storage](https://docs.spring.io/spring-security/reference/features/authentication/password-storage.html), [CSRF considerations](https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html).

Passwords use salted BCrypt with cost 12. The API checks the 72-byte UTF-8 limit explicitly, so Unicode passwords aren't silently truncated. An unknown login still compares against a dummy hash, and known/unknown users get the same bad-credential response.

JWTs identify users without embedding organization roles. Every organization workflow checks current database membership. Owners manage memberships; employees manage customers and draft invoices; accountants can read customers and manage invoices; owners/accountants can issue and void. A user can have different roles in different organizations. Nonmembers get 404, while members lacking a required role get 403.

The API accepts explicit Authorization bearer headers and doesn't authenticate through cookies, sessions, or HTTP Basic. That's why CSRF protection is disabled here; statelessness alone wouldn't justify disabling it for an app using browser-supplied credentials.

Creating an organization and its owner membership is one database transaction. Membership changes lock the organization row before checking the owner count, preventing two concurrent demotions from removing the last owner.

Entities and repositories stay package-private. Other domains use UserDirectory, OrganizationAccess, and CustomerDirectory rather than another domain's repository. Tenant records are queried by organization and ID together. Composite foreign keys also prevent cross-organization customer/invoice and invoice/line associations at the database level.

Invoices support USD for now, integer quantities, two-place prices, and fractional tax rates with up to four places. Tax rounds HALF_UP per line and is then summed. This is a documented project rule, not a claim to implement every jurisdiction's tax policy. Issued invoices copy customer details and reject content edits; VOID is terminal. Invoice issuance and voiding now post to the ledger; payment-backed transitions come next.

Customer/invoice writes use a scoped pessimistic row lock and an explicit, monotonic version checked under that lock. The version is managed by the aggregate rather than JPA's @Version. This serializes writes and reliably rejects stale content, including line-only edits. Invoice line positions use a deferred unique constraint so JPA can replace lines in a single transaction without an intermediate duplicate-position failure. Conflict/rollback and concurrency tests use real PostgreSQL.

Hibernate's value-bearing SQL exception logger is disabled because constraint messages can include submitted emails or invoice references. The API returns deliberate safe messages. The current tests aren't performance measurements.

## How I built the ledger

I kept posting behind `InvoiceLedger`, a public application API owned by the ledger package. It uses JDBC for a small append-only schema; invoice entities stay in JPA. Spring's JPA transaction manager shares the datasource connection with JDBC, and MANDATORY propagation prevents independent ledger commits.

I assume issuance follows delivery: debit receivables for the total, credit revenue for the subtotal, and credit tax payable for tax. Zero-dollar invoices create no journal. A void copies the original entries with debit/credit swapped; a unique original link and exact-reversal check stop double or altered reversals. Originals never change.

Accounts are a fixed USD chart created for each organization. Tenant/currency composite foreign keys prevent mixing scopes. Debits/credits are exact decimal amounts, and balances are calculated from immutable entries. A journal must have at least two positive-sided entries and equal debit/credit sums at commit. Database triggers reject UPDATE/DELETE and entries added after the header's creating transaction. These protect ordinary database writes; a database administrator can disable triggers or truncate tables. Restricted production database roles are still to build.

Invoice row locks, versions, and unique (organization, invoice, operation) sources prevent duplicate postings. Retry responses remain 409 rather than replaying a stored HTTP response. Provider events will need their own persistent idempotency contract when payments are added.

The fourth migration explicitly backfills previous synthetic issued invoices and already-voided reversals, preserving saved totals and timestamps. This uses the same delivery assumption; real-data adoption would require reviewed opening balances and an import policy instead.

Questions I can practice explaining: Why isn't equal debit/credit a row CHECK constraint? Why must invoice state and posting commit together? Why do corrections reverse entries instead of editing them? How does a stale retry avoid posting twice? Why can revenue have a negative debit-minus-credit balance?
