# Local development

## Environment inspected on 2026-09-16

| Tool | Observed | Needed |
| --- | --- | --- |
| Java / javac | Temurin 17.0.20.1 active | ARM64 JDK 21 before Milestone 1 |
| Maven / Gradle | Neither on PATH | Maven Wrapper generated in Milestone 1; Gradle unnecessary |
| Node.js / npm | 24.20.0 / 11.19.0 | Frontend tooling in Milestone 9 |
| Docker client / daemon | Both 29.7.2; daemon responds | PostgreSQL and Testcontainers from Milestone 1 |
| Docker Compose | v5.5.0 | Local infrastructure from Milestone 1 |
| Git | 2.39.3 | Version control; durable checkout now established |
| PostgreSQL tooling | `psql`, `pg_isready` absent | Use utilities inside PostgreSQL container; host install optional |
| Python | 3.12.14 | FastAPI and pytest in Milestone 10; packages not checked yet |
| Terraform | Absent from PATH | Milestone 12 only |

Host architecture is ARM64. macOS Java discovery also lists an older x86_64 Java 17 installation, so its default selection is unsuitable for the required Java 21 setup. Installed Node 24 belongs to the LTS line ([release schedule](https://nodejs.org/en/about/previous-releases)); frontend dependencies will be pinned later.

Optional future tools: an IDE with Java support, Stripe CLI for local webhook forwarding in Milestone 5, and AWS CLI in Milestone 12. Sandbox accounts are needed only at the integration milestones. No cloud account is needed now.

## Commands available now

Run these read-only checks from your cloned repository root:

```sh
cd ledgerflow  # from the parent directory containing your clone
java -version
javac -version
node --version
npm --version
docker --version
docker compose version
docker info --format '{{.ServerVersion}}'
git --version
python3 --version
```

This Git repository is the durable development checkout. The original project mirror remains separate and its synced reference files must not be edited.

## Java prerequisite

Install an ARM64 JDK 21 using a trusted JDK distributor before backend generation. Verify the selected JDK rather than relying on the presence of an installer or an IDE runtime. If it is registered with macOS, run from any directory:

```sh
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
export PATH="$JAVA_HOME/bin:$PATH"
java -version
javac -version
```

Both versions must report 21. This selection command currently cannot succeed because no registered Java 21 installation was found. A manually installed JDK requires setting JAVA_HOME to its actual home directory.

## Commands planned for Milestone 1

These commands will work only after the backend, wrapper, Compose file, environment template, and migrations are created. `LEDGERFLOW_DIR` means the chosen durable checkout's absolute path.

From the future repository root:

```sh
cd "$LEDGERFLOW_DIR"
cp .env.example .env
docker compose up -d postgres
docker compose ps
docker compose exec postgres pg_isready -U ledgerflow -d ledgerflow
docker compose exec postgres psql -U ledgerflow -d ledgerflow
```

The Compose service, database, and local username will be `postgres`, `ledgerflow`, and `ledgerflow`. Choose a local password in the ignored `.env`; the backend must receive matching environment variables separately. Compose's `.env` interpolation does not automatically configure a Spring process started in your shell.

From the future backend directory, after supplying `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, and `SPRING_DATASOURCE_PASSWORD` through your shell/IDE environment:

```sh
cd "$LEDGERFLOW_DIR/backend"
./mvnw --version
./mvnw verify
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

The wrapper downloads the pinned Maven version on first use; dependency downloads require network access. `verify` must include required integration tests rather than leaving them silently skipped. Testcontainers needs the running Docker daemon and supplies isolated test PostgreSQL instances.

Once Milestone 1 starts the app, run from any directory:

```sh
curl -i http://localhost:8080/actuator/health
curl -i http://localhost:8080/v3/api-docs
```

Expected future behavior: health responds successfully with UP, OpenAPI returns a document, and Swagger UI is available at `http://localhost:8080/swagger-ui/index.html`. Health details must not expose secrets; documentation access policy will be revisited with authentication.

Stop future local infrastructure from the repository root with `docker compose down`. This preserves database volumes. Reset procedures that destroy data must be documented separately before use.

## Milestone 0 interview questions

1. Why does one database transaction simplify coordinating invoices and ledger posting?
2. How can a modular monolith become tightly coupled, and how will domain APIs prevent that?
3. What does the Maven Wrapper pin, and why must Java still be configured separately?
4. Why can't a provider API call participate in a PostgreSQL transaction?
5. How does an outbox prevent lost events, and why do consumers still need idempotency?
6. Why must organization isolation apply to database access as well as HTTP authorization?
7. Why are immutable journal entries and action audit logs different responsibilities?
