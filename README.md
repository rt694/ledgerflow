# LedgerFlow

I'm building LedgerFlow to learn how payments, invoices, and bank reconciliation fit together. The idea is a small-business app where I can create invoices, simulate payments, and track the money in a double-entry ledger.

The backend now supports users, organizations, customers, and invoices. I can log in, assign organization roles, create a draft invoice, edit it, issue it, and void it. The ledger, payments, bank connections, and frontend are still to build.

## What I'm using

- Java 21, Spring Boot, and Maven for the backend
- PostgreSQL and Flyway for the database
- React and TypeScript for the frontend
- Stripe and Plaid sandboxes for simulated payments and bank data
- Kafka for background events, and Python/FastAPI for later analytics
- Docker Compose for local development

I'm starting with one backend organized by business domain. That lets me learn the financial workflows without managing a bunch of services right away. Redis, monitoring tools, and Terraform can come later when there's a reason to add them.

## Working so far

- Registration/login with hashed passwords and signed JWTs
- Organizations with owner, employee, and accountant memberships
- Organization-scoped customers and paginated lists
- USD invoices with decimal calculations and per-line tax rounding
- Draft/issued/void states, fixed issued content, and version checks for stale edits
- PostgreSQL migrations, health checks, validation, consistent errors, and local Swagger UI

Next is the double-entry ledger. After that I'll add simulated payments, bank syncing, reconciliation, and a frontend dashboard.

Everything uses fake data. This project won't handle real money or real bank credentials. Stripe/Plaid integrations will use sandboxes, and any AWS deployment comes after checking costs and getting approval.

## Project notes

- [Build checklist](docs/ROADMAP.md)
- [Architecture sketch](docs/ARCHITECTURE.md)
- [Local setup](docs/LOCAL_DEVELOPMENT.md)
- [Try registration, organizations, and invoices](docs/API_WALKTHROUGH.md)
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
./scripts/generate-local-keys.sh
```

Follow [local setup](docs/LOCAL_DEVELOPMENT.md) to select Java 21, load the database settings, run tests, and start the backend. The local API uses port **18080** and PostgreSQL uses **55432** so they can sit alongside other projects.

Once the app is running:

- Health: `http://localhost:18080/actuator/health`
- Swagger UI: `http://localhost:18080/swagger-ui/index.html`
- OpenAPI: `http://localhost:18080/v3/api-docs`

In Swagger UI, register and log in, then use **Authorize** with the returned token. The [walkthrough](docs/API_WALKTHROUGH.md) explains the role permissions and invoice requests. The app listens on localhost.

## Checking the backend

From `backend/`, with Java 21 selected and Docker running:

```sh
./mvnw verify
```

This checks Java formatting, compiles the app, runs 19 focused tests, packages an executable JAR, and runs 42 integration tests against isolated PostgreSQL through Testcontainers. Integration tests fail if Docker is unavailable rather than silently skipping.

From the repository root, with the local app running, `python3 scripts/smoke-workflow.py` checks a synthetic registration-to-invoice workflow over HTTP without printing tokens.

See the [setup notes](docs/LOCAL_DEVELOPMENT.md) for requests you can try by hand. These tests aren't performance measurements; there are no latency or throughput claims yet.
