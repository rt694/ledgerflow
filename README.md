# LedgerFlow

I'm building LedgerFlow to learn how payments, invoices, and bank reconciliation fit together. The idea is a small-business app where I can create invoices, simulate payments, and track the money in a double-entry ledger.

It's still in the planning stage. There's no working app yet; the backend setup is next.

## What I'm using

- Java 21, Spring Boot, and Maven for the backend
- PostgreSQL and Flyway for the database
- React and TypeScript for the frontend
- Stripe and Plaid sandboxes for simulated payments and bank data
- Kafka for background events, and Python/FastAPI for later analytics
- Docker Compose for local development

I'm starting with one backend organized by business domain. That lets me learn the financial workflows without managing a bunch of services right away. Redis, monitoring tools, and Terraform can come later when there's a reason to add them.

## What I want to build

- Users, organizations, customers, and invoices
- Simulated payments and bank connections
- A balanced, auditable ledger with reversals for corrections
- Rules for matching transactions and a queue for reviewing unclear matches
- A dashboard showing invoices, balances, and reconciliation results

Everything will use fake data and sandbox accounts. This project won't handle real money or real bank credentials. Any AWS deployment comes later, after checking costs.

## Project notes

- [Build checklist](docs/ROADMAP.md)
- [Architecture sketch](docs/ARCHITECTURE.md)
- [Local setup](docs/LOCAL_DEVELOPMENT.md)
- [Progress](PROJECT_STATUS.md)
- [Why I chose this approach](DECISIONS.md)

## Getting started

Run these from the directory where you want to keep the project:

```sh
git clone https://github.com/rt694/ledgerflow.git
cd ledgerflow
```

For now, there's only documentation. Before building the backend, I need Java 21. The Maven Wrapper and a PostgreSQL Compose setup will be added with the backend, so a global Maven installation and a native PostgreSQL server won't be required.

The [local setup notes](docs/LOCAL_DEVELOPMENT.md) separate commands that work now from commands planned for the app. So far, I've checked the local tools and documentation links; there aren't any application tests or performance results yet.
