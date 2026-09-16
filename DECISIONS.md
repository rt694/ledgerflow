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
