# LedgerFlow

A distributed financial transaction and payment platform whose PostgreSQL
database is the transactional source of truth — double-entry ledger, ACID
money movement, idempotent APIs, transactional outbox, and measured (never
fabricated) performance work.

> **Status: under construction, built phase by phase.** This README lists only
> what exists and is verified today; the full README lands with the final
> phase. See [docs/architecture.md](docs/architecture.md) and
> [docs/database-design.md](docs/database-design.md).

## What works today (Phase 1)

- PostgreSQL 17 schema via Flyway: users, accounts, hot balance rows,
  **monthly-partitioned** `transactions` + `ledger_entries`, payments/refunds/
  disputes, idempotency keys, transactional outbox, audit log, fraud decisions.
- **Double-entry invariant enforced by the database**: a deferred constraint
  trigger rejects any commit whose ledger entries don't sum to zero.
- Ledger and audit rows are append-only at the database level.
- Balance floors (`CHECK`) as the final backstop against double spending.
- Spring Boot 3.5 skeleton with health probes; integration tests run the real
  schema on PostgreSQL 17 Testcontainers.

## Local development

```bash
export JAVA_HOME="$(brew --prefix openjdk@21)/libexec/openjdk.jdk/Contents/Home"

docker compose up -d postgres    # PostgreSQL 17 on localhost:55432
mvn verify                        # build + unit + integration tests
mvn spring-boot:run               # Flyway migrates on boot; app on :8080
curl localhost:8080/actuator/health
```

Note for Docker Engine ≥ 29: Testcontainers 1.x needs
`src/test/resources/docker-java.properties` with `api.version=1.44`
(already in this repo).
