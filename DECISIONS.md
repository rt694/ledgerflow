# Why I chose this approach

These are my starting choices. I'll update them as I build and learn what works.

## One backend to start

I'm starting with a modular monolith: one Spring Boot app, with packages organized around business domains. One PostgreSQL database makes it possible to update business records and ledger entries in the same transaction. The tradeoff is that everything shares a process and gets released together. I'll consider separate services if I find a concrete need for them.

## Java 21 and Maven

Maven's standard layout and build lifecycle are enough for this project. A pinned Maven Wrapper will make the build tool consistent across machines, though Java still needs to be installed separately. I'll choose compatible stable Spring Boot and library versions when setting up the backend.

References: [Spring Boot requirements](https://docs.spring.io/spring-boot/system-requirements.html), [Maven Wrapper](https://maven.apache.org/tools/wrapper/).

## Packages by domain

Customers, invoices, payments, banking, and the ledger will each own their code and data access. If one domain needs another, it should call that domain's application API rather than reaching into its repositories. I'll keep shared code small and avoid adding empty layers just to fit a pattern.

## Money and organization boundaries

Money will use Java BigDecimal and PostgreSQL NUMERIC, with an explicit ISO currency code. I'll define currency scale and rounding before implementing calculations. Posted journals must balance within one currency and stay immutable; corrections will use linked reversals or compensating entries.

Database constraints should back up application validation. Balancing multiple rows can't be enforced with a simple CHECK constraint, so that needs careful design when I build the ledger.

Tenant-owned data will be scoped to an organization. Access must come from authenticated membership, with organization checks in queries and constraints where practical. I'll test cross-organization access attempts. Row-level security is something to revisit once I understand the connection-pool implications.

## Background events later

I'll use local domain calls first. When I add Kafka, an outbox will store events in the same transaction as business changes, then publish them afterward. Delivery can happen more than once, so consumers need idempotency. Provider webhooks need duplicate and ordering safeguards even before Kafka is added.

Redis can help with something like rate limiting or disposable caching later. It won't be the source of truth for balances or payment idempotency.

## Fake data only

Stripe will use test/sandbox credentials and Plaid will use Sandbox. Secrets stay out of Git and logs. AWS is optional: I'll get the app working locally, estimate costs, and get explicit approval before creating paid resources.

## Backend setup choices

I chose Spring Boot 3.5.16 for the requested JUnit 5 stack and familiar Spring MVC/JPA setup. It's a stable 3.5 release; newer Boot major versions are available, so this is a deliberate compatibility choice rather than a claim to use the latest major. springdoc 2.8.17 documents this Boot 3 backend. Maven 3.9.16 and PostgreSQL 17.10 are pinned.

References: [Boot 3.5 requirements](https://docs.spring.io/spring-boot/3.5/system-requirements.html), [springdoc Boot 3 documentation](https://springdoc.org/v2/).

Flyway owns schema changes; Hibernate validates mapped entities. The first migration only creates the `ledgerflow` schema, so no business tables are implied. I disabled Open Session in View to keep future database access in explicit application transactions.

API errors use Spring ProblemDetail, with an HTTP code and a list of field errors for validation. I preserve framework response headers such as Allow, and replace internal error details with safe messages. Authentication and its error handling are still to build.

The greeting endpoint is a temporary, stateless way to test the foundation. It doesn't belong to a financial domain and doesn't save data. Business code will be added by domain as those features are built.

PostgreSQL integration tests run in Failsafe during `verify` using Testcontainers. They're required and fail without Docker. Spotless checks Java formatting during validation. Local API/database ports are 18080/55432 because another project was already using 8080/5432. Both listen on localhost, and API docs are enabled only in local/test profiles.

Flyway's history is explicitly kept in `public`, while business tables will live in `ledgerflow`. PostgreSQL's default search path can change when a schema matches the login username; leaving history placement implicit caused a second startup to try the first migration again. A fresh-connection regression test now uses the local database username to cover that case.
