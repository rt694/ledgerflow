# LedgerFlow

LedgerFlow is a planned small-business payments and reconciliation portfolio project. It will use simulated Stripe payments and Plaid bank accounts, an auditable double-entry ledger, and explainable reconciliation rules.

**Current state: Milestone 0 planning complete. No application has been implemented.**

All financial data must be synthetic. Stripe must use test/sandbox credentials; Plaid must use Sandbox. No real money, bank credentials, or customer financial data are permitted. AWS resources require explicit approval after costs are explained.

## Architecture and stack

Start with a Java 21 / Spring Boot modular monolith, organized by domain, backed by PostgreSQL and Flyway. Use Maven with a pinned Maven Wrapper. Add React/TypeScript, Kafka, Redis, Python/FastAPI, observability, and Terraform only at the milestones where they serve a concrete purpose.

A single application and database make atomic business and ledger updates practical. Domain APIs and ownership boundaries keep future extraction possible without introducing distributed transactions now.

## Planning documents

- [Architecture and proposed structure](docs/ARCHITECTURE.md)
- [Environment findings and local commands](docs/LOCAL_DEVELOPMENT.md)
- [Milestones and acceptance gates](docs/MILESTONES.md)
- [Project status](PROJECT_STATUS.md)
- [Architectural decisions](DECISIONS.md)

## Getting started

Clone this public repository into a local development directory:

```sh
git clone https://github.com/rt694/ledgerflow.git
cd ledgerflow
```

This repository contains planning documents only. Backend generation begins in Milestone 1.

Java 21 is the next environment prerequisite; the active JDK is currently Java 17. Docker is running. No global Maven installation is required once Milestone 1 adds the wrapper. PostgreSQL will run through Docker Compose; installing a native database server is unnecessary.

Use the directory-specific commands in [LOCAL_DEVELOPMENT.md](docs/LOCAL_DEVELOPMENT.md). Application, Compose, and test commands there are explicitly marked as future commands until their files exist.

## Verification

Milestone 0 verification consists of inspecting installed tools, confirming the Docker daemon responds, and checking planning document consistency and links. There is no source code to compile, format, or test yet. No performance claims or measured results exist.

Suggested initial branch: `docs/milestone-0-planning`.

Suggested commit message: `docs: define LedgerFlow architecture and milestone plan`.
