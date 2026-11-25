# PLUTUS

PLUTUS is an AI back-office for small merchants and freelancers in India. It starts with the most
tedious job in a small business's books — working out which payment paid which invoice. Upload your
bank/UPI and PayPal statements alongside your invoices, and PLUTUS reconciles them automatically:
confident matches are confirmed for you, and only the genuinely ambiguous ones land in a review queue.

## Stack
- Java 21, Spring Boot 3.5, Maven (wrapper included)
- PostgreSQL 16, Flyway migrations, Spring Data JPA
- JUnit 5 + Testcontainers
- GitHub Actions CI

## Running locally
Prerequisites: JDK 21 and Docker.

```bash
docker compose up -d          # start Postgres 16 on localhost:5432
./mvnw spring-boot:run        # start the app on localhost:8080
curl localhost:8080/actuator/health
```

Run the build and tests (needs Docker running for Testcontainers):

```bash
./mvnw verify
```

## Roadmap
- **Phase 1 — Payment reconciliation (current).** Ingest bank/UPI/PayPal CSVs and invoices; match via
  exact reference → exact amount + name → fuzzy scoring → split payments (subset-sum); auto-confirm
  high-confidence matches, queue the rest for review.
- **Phase 2 — Firewall.** Every money-moving action (refunds, payouts) must pass a merchant-defined
  mandate check, executed idempotently with a full audit trail.
- **Phase 3 — Agent & dashboard.** An AI agent proposes actions (reminders, refunds) through the
  firewall, with a React dashboard for merchants.

## Design decisions
_To be filled in as the project evolves._
