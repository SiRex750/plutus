# PLUTUS — project context

## Product
PLUTUS is an AI back-office for small merchants and freelancers in India.

**Phase 1 (current): payment reconciliation service.**
The merchant uploads payment statements (bank/UPI CSV, PayPal CSV) and their invoices.
PLUTUS matches payments to invoices using a cascade:
1. Exact reference match
2. Exact amount + payer name
3. Fuzzy scoring
4. Split payments via subset-sum (one invoice paid by several payments, or vice versa)

High-confidence matches are auto-confirmed; everything else goes to a review queue.

**Phase 2: the "firewall".** Every money-moving action (refunds, payouts) must pass a
merchant-defined mandate check, with idempotent, audited execution.

**Phase 3: AI agent + dashboard.** An AI agent proposes actions (payment reminders, refunds)
that go through the firewall; plus a React dashboard.

## Tech stack
- Java 21, Spring Boot 3.5.x, Maven via the committed wrapper (`./mvnw`)
- Base package: `com.plutus`
- PostgreSQL 16 (`docker-compose.yml`: db/user/password `plutus`, port 5432)
- Flyway for all schema changes (`src/main/resources/db/migration`)
- JPA with `ddl-auto: validate` — Hibernate never changes the schema
- Tests: JUnit 5, Spring Boot Test, Testcontainers (Postgres). Docker must be running.

## Conventions
- **Money is always `long` minor units (paise).** Never `double`/`float`/`BigDecimal` for stored or
  computed amounts. A small `Money` helper will be added later; until then, follow the rule.
- **No direct `now()` calls in services.** Never call `LocalDate.now()` / `Instant.now()` etc.;
  inject the `java.time.Clock` bean (`com.plutus.config.TimeConfig`) so tests control time.
- **Schema changes only through Flyway migrations.** Never edit an applied migration; add a new one.
- **Business logic (matching) is plain Java with no Spring dependencies**, so it is unit-testable
  without a database or application context.
- **Every feature = one small commit** with a clear message. Tests live next to the feature they cover
  (same package under `src/test/java`).

## Commands
- Start DB: `docker compose up -d`
- Run app: `./mvnw spring-boot:run` (health: `GET http://localhost:8080/actuator/health`)
- Build + all tests: `./mvnw verify`

## Local dev notes (Windows)
- If the app dies at startup with `Unable to establish loopback connection` /
  `Invalid argument: connect`, the JDK's AF_UNIX selector pipe can't use the user temp dir
  (the Windows username contains a space). Point it elsewhere:
  `java -Djdk.net.unixdomain.tmpdir=C:\Windows\Temp -jar target/plutus-*.jar`, or
  `./mvnw spring-boot:run -Dspring-boot.run.jvmArguments=-Djdk.net.unixdomain.tmpdir=C:\Windows\Temp`.
