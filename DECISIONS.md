# Architectural decisions

Decisions below guide future implementation; they do not imply the described functionality exists.

## ADR-001 — Modular monolith first

**Status:** Accepted for initial implementation.

Use one Spring Boot deployable, organized by business domain, and one PostgreSQL database. Business workflows can use local calls and database transactions. This reduces operational overhead and makes financial atomicity easier to explain and verify. The tradeoff is coordinated releases and shared process failures. Extract services only when measured scaling, ownership, or deployment requirements justify the added network and consistency costs.

## ADR-002 — Maven and Java 21

**Status:** Accepted for initial implementation.

Use Java 21 and Maven with a pinned wrapper. Maven's conventional layout and explicit dependency lifecycle are sufficient for this backend and easier to review than custom build logic. Gradle is viable, particularly for complex multi-project builds, but adds no required capability here. The wrapper pins Maven, not the JDK: local development and CI must separately select Java 21.

Generate a stable Spring Boot version compatible with Java 21 and the intended dependencies in Milestone 1. Recheck compatibility at generation time rather than freezing a version in a planning document.

References: [Spring Boot requirements](https://docs.spring.io/spring-boot/system-requirements.html), [Maven Wrapper](https://maven.apache.org/tools/wrapper/).

## ADR-003 — Explicit domain ownership

**Status:** Accepted for initial implementation.

Domain packages expose narrow application APIs and keep entities/repositories internal. Cross-domain workflows call those APIs, never another domain's repository. Start with one Maven module and domain packages; separate build modules only if dependency enforcement warrants them. Keep `shared` small and free of business entities.

## ADR-004 — Financial correctness and tenant isolation

**Status:** Accepted requirements; schema details deferred to relevant milestones.

Use Java BigDecimal and PostgreSQL NUMERIC with explicit ISO currency codes. Define scale and rounding policies per supported currency before implementing calculations. Posted journals balance within one currency, remain immutable, and are corrected by linked reversals or compensating transactions. Enforce practical invariants in database constraints as well as application validation. Cross-row balancing and immutability require deliberate database enforcement in Milestone 4, not an assumed simple CHECK constraint.

Scope tenant-owned records and every access path by organization. Derive access from authenticated membership rather than trusting a request's organization identifier. Composite foreign keys and unique constraints should prevent cross-organization associations where practical. Test denial and leakage cases. PostgreSQL row-level security remains a later defense-in-depth decision, with connection-pool behavior considered first.

## ADR-005 — Reliable events without premature infrastructure

**Status:** Accepted direction; implementation deferred.

Initially use synchronous domain calls for atomic workflows. In Milestone 8, write outbox events in the same database transaction as business changes, then publish them to Kafka. Expect at-least-once delivery and use idempotent consumers; do not promise exactly-once external effects. Webhook deduplication and ordering safeguards are required earlier in the integration milestones.

Redis must have a concrete purpose, such as rate limiting or disposable caching. It is not the ledger, a balance authority, or a substitute for database-backed idempotency.

## ADR-006 — Synthetic data and staged deployment

**Status:** Accepted constraint.

Use only Stripe test/sandbox and Plaid Sandbox. Validate environment/key compatibility, keep secrets outside version control, and avoid sensitive data in logs. AWS deployment is optional and comes after local acceptance gates, a cost estimate, and explicit resource-creation approval. No paid cloud service is required for local development.
