# Database Design

PostgreSQL is the **only** source of truth for financial state. Everything else
(Redis caches, Kafka events, analytics) is derived from it and can be rebuilt
from it. This document explains the schema; the migration itself is
[V1__initial_schema.sql](../src/main/resources/db/migration/V1__initial_schema.sql).

## ER Diagram

```mermaid
erDiagram
    users ||--o{ accounts : owns
    users ||--o{ payment_methods : registers
    users ||--o{ idempotency_keys : scopes
    users ||--o{ notifications : receives
    accounts ||--|| account_balances : "1:1 hot row"
    accounts ||--o{ ledger_entries : "posted to"
    transactions ||--|{ ledger_entries : "balanced set (sum=0)"
    transactions ||--o{ transaction_events : "status history"
    idempotency_keys |o--o{ transactions : "created by request"
    payments ||--o{ refunds : "refunded by"
    payments ||--o{ disputes : "disputed by"
    payments |o--|| transactions : "settled by"
    refunds |o--|| transactions : "settled by"
    payments |o--o| payment_methods : "funded via"
    payments ||--o{ fraud_decisions : "evaluated by"
    outbox_events ||--o{ processed_events : "deduped by consumers"

    users {
        uuid id PK
        text email "unique on lower(email)"
        text password_hash
        text role "USER|ADMIN"
        text status
    }
    accounts {
        uuid id PK
        uuid user_id FK "NULL for SYSTEM_*"
        text type "USER_WALLET|MERCHANT|SYSTEM_*"
        char3 currency
        text status "ACTIVE|FROZEN|CLOSED"
    }
    account_balances {
        uuid account_id PK_FK
        bigint balance "minor units"
        bigint min_balance "NULL = no floor"
        bigint version
    }
    transactions {
        uuid id PK "partitioned monthly"
        timestamptz created_at PK
        text type "DEPOSIT|WITHDRAWAL|TRANSFER|PAYMENT|REFUND"
        text status "PENDING|COMPLETED|FAILED|REVERSED"
        bigint amount "always > 0"
        uuid source_account_id FK
        uuid destination_account_id FK
        uuid idempotency_key_id FK
    }
    ledger_entries {
        uuid id PK "partitioned monthly"
        uuid transaction_id FK
        timestamptz created_at PK_FK
        uuid account_id FK
        bigint amount "signed, != 0"
        text direction "generated: CREDIT|DEBIT"
    }
    idempotency_keys {
        uuid id PK
        uuid user_id FK
        text endpoint
        text idem_key "UNIQUE(user,endpoint,key)"
        text request_hash
        text status "IN_PROGRESS|COMPLETED|FAILED"
        jsonb response_body
    }
    payments {
        uuid id PK
        uuid payer_user_id FK
        uuid destination_account_id FK
        bigint amount
        bigint refunded_amount "CHECK <= amount"
        text status
    }
    outbox_events {
        uuid id PK
        text topic
        text event_type
        jsonb payload
        text status "PENDING|PUBLISHED|FAILED"
    }
    audit_logs {
        uuid id PK
        uuid actor_user_id FK
        text action
        text resource_type
        jsonb previous_state
        jsonb new_state
    }
```

## The accounting model

Every money movement is one row in `transactions` plus **two or more signed
rows** in `ledger_entries` that sum to zero. Positive amounts credit an
account, negative amounts debit it.

External money is modeled with **system accounts** instead of one-sided
entries:

| Movement | Debit (−) | Credit (+) |
|---|---|---|
| Deposit $100 | `SYSTEM_CASH` −100 | user wallet +100 |
| Withdrawal $40 | user wallet −40 | `SYSTEM_CASH` +40 |
| Transfer $25 A→B | wallet A −25 | wallet B +25 |
| Payment $30 + $1 fee | wallet −31 | merchant +30, `SYSTEM_FEES` +1 |

`SYSTEM_CASH` legitimately goes negative. It mirrors money the platform holds
externally. That's why `account_balances.min_balance` is `NULL` (no floor) for
system accounts and `0` for user wallets.

### Invariants and where they are enforced

| Invariant | Enforcement | Why not application-only |
|---|---|---|
| Entries of a transaction sum to 0 | **deferred constraint trigger** per ledger partition, checked at COMMIT | any code path that forgets an entry is rejected by the database itself |
| No balance below floor (no double spend) | `CHECK (min_balance IS NULL OR balance >= min_balance)` | last line of defense even if locking is buggy |
| Ledger/audit rows immutable | `BEFORE UPDATE OR DELETE` trigger raising an exception | history that can be edited is not an audit trail |
| No duplicate payment per client retry | `UNIQUE (user_id, endpoint, idem_key)` | uniqueness under concurrency is a database problem |
| Refunds never exceed the payment | `CHECK (refunded_amount <= amount)` | concurrent partial refunds must not oversell |
| Amounts are valid | `CHECK (amount > 0)` on transactions, `CHECK (amount <> 0)` on entries | garbage amounts stopped at the boundary |

## Key modeling decisions

**Money is `BIGINT` minor units.** No `NUMERIC` in the hot path (slower, and
we never need fractional cents for these currencies), never floating point.
Currency lives beside every amount; cross-currency movements are rejected in
Phase 3 logic (a future FX feature would introduce explicit conversion
transactions).

**`account_balances` is separate from `accounts`.** The balance row is the
single most contended row in the system, since every movement takes `SELECT ... FOR
UPDATE` on it. Keeping it narrow (no index on `balance`, cold metadata
elsewhere) keeps lock scope tight and updates HOT (no index-entry churn).
The balance is deliberately *derivable*: `SUM(ledger_entries.amount) per
account` must always equal it, which reconciliation queries assert.

**TEXT + CHECK instead of Postgres ENUM types.** Adding an enum value is
`ALTER TYPE` with ordering/locking caveats; extending a CHECK is a plain
constraint swap. The type-safety is equivalent for our purposes.

**UUIDv7 primary keys, generated in the application.** Random UUIDv4 keys
splatter inserts across the whole B-tree; UUIDv7 is time-ordered so inserts
append like a sequence, and `(created_at, id)` keyset pagination gets a
natural tiebreaker. App-side generation lets the service construct the full
object graph (transaction + entries + outbox) before touching the database.

**Partitioning from day one** for `transactions` and `ledger_entries`:
monthly `RANGE (created_at)` partitions. Both tables are append-only,
unbounded, and nearly always filtered by time window, which is the textbook
partitioning case: time-range queries prune to 1–2 partitions, and archival
is `DETACH PARTITION`, not `DELETE`. Consequences we accept and document:

- the partition key must be in the PK → `PRIMARY KEY (id, created_at)`;
  `id` uniqueness across partitions is guaranteed by construction (UUIDv7).
- `ledger_entries.created_at` always equals its transaction's `created_at`
  (enforced by the composite FK), so joins prune both tables together.
- constraint triggers cannot live on the partitioned parent, so
  `create_month_partitions()` attaches the zero-sum trigger to each new
  partition. Partitions are pre-created months ahead; there is deliberately
  **no DEFAULT partition**. A default absorbs mis-ranged rows silently and
  blocks clean partition management. Missing-partition inserts fail loudly
  and partition coverage is monitored.

**Normalization.** The schema is 3NF with two deliberate denormalizations:
`account_balances.balance` (a running aggregate of the ledger, maintained
transactionally, because computing `SUM()` per read does not scale) and
`payments.refunded_amount` (aggregate of refunds, guarded by CHECK + row
lock, so the over-refund rule is enforceable in one place).

## Indexing status

V1 ships only indexes with a proven job (PK/unique constraints, FK lookup
paths like `accounts.user_id`, the partial index on pending outbox rows, and
audit lookup paths). Query-driven indexes are added **with the queries that
need them** in Phase 4, each documented with plan evidence in
[query-optimization.md](query-optimization.md).

## Verification

`SchemaInvariantsIT` (Testcontainers, real PostgreSQL 17) proves at CI time:
unbalanced transactions cannot commit, balanced ones can, ledger entries
reject UPDATE/DELETE, and balances cannot pass their floor. The same checks
were run by hand against the compose database (see docs/verification notes in
each phase's commit message).
