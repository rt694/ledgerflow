# LedgerFlow

I'm building LedgerFlow to learn how payments, invoices, and bank reconciliation fit together. The idea is a small-business app where I can create invoices, simulate payments, and track the money in a double-entry ledger.

The backend foundation now runs locally: PostgreSQL, migrations, health checks, API docs, and a small endpoint for trying validation. Users, invoices, and financial features are still to build.

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

You'll need Java 21 and a running Docker daemon. Maven is downloaded through the wrapper, and PostgreSQL runs in Docker Compose.

From the repository root, create your local settings and start the database:

```sh
cp .env.example .env
# Replace the password placeholder in .env before continuing.
docker compose up -d --wait postgres
```

Follow [local setup](docs/LOCAL_DEVELOPMENT.md) to select Java 21, load the database settings, run tests, and start the backend. The local API uses port **18080** and PostgreSQL uses **55432** so they can sit alongside other projects.

Once the app is running:

- Health: `http://localhost:18080/actuator/health`
- Swagger UI: `http://localhost:18080/swagger-ui/index.html`
- OpenAPI: `http://localhost:18080/v3/api-docs`

Authentication is next. For now, the app listens on localhost and the example endpoint saves nothing.

## Checking the backend

From `backend/`, with Java 21 selected and Docker running:

```sh
./mvnw verify
```

This checks Java formatting, compiles the app, runs 9 focused tests, packages an executable JAR, and runs 5 integration tests against isolated PostgreSQL through Testcontainers. Integration tests fail if Docker is unavailable rather than silently skipping.

See the [setup notes](docs/LOCAL_DEVELOPMENT.md) for requests you can try by hand. These tests aren't performance measurements; there are no latency or throughput claims yet.
