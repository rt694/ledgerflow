# Local setup

## What you need

- Java 21 (both `java` and `javac` should report 21)
- A running Docker daemon and Docker Compose
- Git

Maven comes through the wrapper. PostgreSQL runs in a container, so you don't need a host installation. Node, Python, and Terraform aren't required to run this backend.

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
cd backend
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

The local API listens on `127.0.0.1:18080`. Set SERVER_PORT if you need another port. The default profile requires explicit database settings and keeps API docs disabled; the `local` profile supplies local connection defaults and enables Swagger UI.

Flyway applies migrations before JPA starts and keeps its history in `public` so the schema search path cannot move it between restarts. Hibernate validates mapped tables and never creates or updates them. There aren't any business entities yet; the first migration creates the application schema. Open Session in View is disabled so later database work stays in the application transaction boundary.

## Try it out

With the app running, run these from any directory:

```sh
curl -i http://localhost:18080/actuator/health
curl -i http://localhost:18080/v3/api-docs
curl -i -X POST http://localhost:18080/api/v1/greetings \
  -H 'Content-Type: application/json' -d '{"name":"Student"}'
curl -i -X POST http://localhost:18080/api/v1/greetings \
  -H 'Content-Type: application/json' -d '{"name":" "}'
```

Expect health to return `UP`, OpenAPI to describe the greeting endpoint, a valid request to return `Hello, Student!`, and a blank name to return HTTP 400 with a field error. Open `http://localhost:18080/swagger-ui/index.html` in your browser to try the endpoint there.

Errors use `application/problem+json`, with standard `type`, `title`, `status`, `detail`, and `instance` fields, plus an HTTP code and field errors when applicable. Rejected values and internal exception messages are omitted. This currently covers MVC validation, parsing, routing, content-type/method errors, and unexpected controller errors. Security errors will need their own integration with this format when authentication is added.

Only Actuator health is exposed; component details are hidden. There is no login or organization data yet. Don't expose this foundation publicly as an application; GitHub hosts the source code only.

## Stop it

Press Ctrl+C in the app terminal. From the repository root:

```sh
docker compose down
```

This keeps the database volume. Don't use `down -v` unless you intend to erase local data. Changing the password in `.env` doesn't change an already initialized database user's password; use the existing password or deliberately reset only disposable data.

## Questions I want to be able to answer

1. Why use Flyway migrations and `ddl-auto: validate` instead of letting Hibernate update the schema?
2. What does `@Valid` do, and how do field errors become an HTTP 400 response?
3. Why use Problem Details, and why avoid returning parser or exception messages?
4. Why test with PostgreSQL instead of an in-memory database?
5. What's the difference between Surefire's focused tests and Failsafe's integration tests?
6. Why doesn't a Compose `.env` automatically configure an app started in a separate shell?
7. What changes when Open Session in View is disabled?
