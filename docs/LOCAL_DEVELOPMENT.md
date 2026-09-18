# Local setup

## What you need

- Java 21 (both `java` and `javac` should report 21)
- A running Docker daemon and Docker Compose
- Git

OpenSSL is needed once to generate your local JWT signing keys. Python 3 is optional for the smoke-check script. Maven comes through the wrapper. PostgreSQL runs in a container, so you don't need a host installation. Node and Terraform aren't required to run this backend.

The backend uses Spring Boot 3.5.16, Maven 3.9.16, springdoc 2.8.17, and PostgreSQL 17.10. Java 21 is enforced at build time. Maven's download checksum is pinned too.

## Select Java

If Java 21 is registered with macOS, run from any directory:

```sh
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
export PATH="$JAVA_HOME/bin:$PATH"
java -version
javac -version
```

For this project's local setup, Temurin 21.0.12.1 was installed in a user tools directory. On that machine, use this instead:

```sh
export JAVA_HOME="$HOME/.local/opt/temurin-21.0.12.1/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"
```

This doesn't change your global Java selection. Other machines should point JAVA_HOME at their own JDK 21 installation.

## Start the database

From your repository root (`ledgerflow/`):

```sh
cp .env.example .env
```

Edit `.env` and replace the password placeholder with a local-only password. Keep assignments shell-compatible; quote values containing special characters. The file is ignored by Git. Only copy the template on first setup so you don't overwrite an existing password.

Then, from the repository root:

```sh
docker compose up -d --wait postgres
docker compose ps
docker compose exec postgres pg_isready -U ledgerflow -d ledgerflow
```

The database is `ledgerflow`, the user is `ledgerflow`, and the host port defaults to 55432. The port and password come from `.env`. Data is stored in a named volume.

## Generate local signing keys

From the repository root, run once:

```sh
./scripts/generate-local-keys.sh
```

This creates an RSA key pair in ignored `.local/` with restricted local permissions and never overwrites an existing pair. Keep both keys together. They're used to sign/verify local JWTs and must not be committed. Tests generate a separate, temporary key pair automatically.

## Run tests

From `ledgerflow/backend/`, with Java 21 selected and Docker running:

```sh
./mvnw --version
./mvnw verify
```

The first run downloads Maven and dependencies. Testcontainers starts its own temporary PostgreSQL database and cleans it up afterward; tests don't use your development database or `.env` credentials. `verify` runs focused tests through Surefire and `*IT` integration tests through Failsafe. There is no Docker-unavailable skip.

To apply Java formatting, also from `backend/`:

```sh
./mvnw spotless:apply
```

## Run the app

Compose reads `.env` for the container, but it doesn't automatically give those values to a Java process in your shell. Load your own trusted, shell-compatible `.env` and map its settings to Spring.

From the repository root, in the terminal where you selected Java 21:

```sh
set -a
source .env
set +a
export SPRING_DATASOURCE_URL="jdbc:postgresql://localhost:${POSTGRES_PORT:-55432}/ledgerflow"
export SPRING_DATASOURCE_USERNAME=ledgerflow
export SPRING_DATASOURCE_PASSWORD="$POSTGRES_PASSWORD"
export JWT_PRIVATE_KEY_PATH="$PWD/.local/jwt-private.pem"
export JWT_PUBLIC_KEY_PATH="$PWD/.local/jwt-public.pem"
cd backend
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

The local API listens on `127.0.0.1:18080`. Set SERVER_PORT if you need another port. The default profile requires explicit database settings and keeps API docs disabled; the `local` profile supplies local connection defaults and enables Swagger UI.

Flyway applies migrations before JPA starts and keeps its history in `public` so the schema search path cannot move it between restarts. Hibernate validates mapped tables and never creates or updates them. The migrations create the application schema, users, organizations, memberships, customers, invoices, invoice lines, ledger accounts, journal transactions, journal entries, payment attempts, refund requests, and processed event references. Open Session in View is disabled so later database work stays in the application transaction boundary.

## Try it out

With the app running, run these from any directory:

```sh
curl -i http://localhost:18080/actuator/health
curl -i http://localhost:18080/v3/api-docs
```

Health returns `UP`; OpenAPI describes the endpoints. Open `http://localhost:18080/swagger-ui/index.html` for the full API. Registration and login are public; the greeting endpoint and business APIs now require a bearer token.

See [the API walkthrough](API_WALKTHROUGH.md) for registration, login, organization membership, customers, and invoices. Use Swagger's **Authorize** button with the login response's `accessToken` (without adding the word Bearer).

From the repository root, with the app running, you can also run:

```sh
python3 scripts/smoke-workflow.py
```

This makes only synthetic accounts and business records, checks the draft/issue/void workflow and tenant denial over HTTP, and prints no credentials or tokens. Those demo records remain in the local database. It isn't a performance test.

Errors use `application/problem+json`, with standard `type`, `title`, `status`, `detail`, and `instance` fields, plus a code and field errors when applicable. Authentication errors use the same format; 401 responses from the security filter include `WWW-Authenticate: Bearer`. Rejected values, internal exception messages, and value-bearing database constraint logs are omitted.

Only Actuator health is exposed; component details are hidden. The default profile disables API docs. Keep the application local during development; GitHub hosts the source code only.

## Stop it

Press Ctrl+C in the app terminal. From the repository root:

```sh
docker compose down
```

This keeps the database volume. Don't use `down -v` unless you intend to erase local data. Changing the password in `.env` doesn't change an already initialized database user's password; use the existing password or deliberately reset only disposable data.

## Questions I want to be able to answer

1. Why keep organization roles in the database instead of embedding them in JWTs?
2. What does JWT signature validation prove, and what do issuer, audience, and expiry checks add?
3. Why is disabling CSRF reasonable for this explicit bearer-token API, but not every stateless app?
4. How do organization-scoped queries and composite foreign keys work together?
5. Why copy customer details when issuing an invoice?
6. How can per-line tax rounding differ from rounding one aggregate tax amount?
7. How do a row lock and an expected version prevent stale writes?
8. Why must the last-owner check run under a shared organization lock?
9. How does an invoice-number conflict prove the transaction rolls back line replacements too?

## Optional sandbox payments

Payments default to disabled. See [Stripe Sandbox setup](STRIPE_SANDBOX.md) to configure test credentials, start CLI forwarding, and run the payment/refund smoke check. Local fixture tests need no Stripe account.
