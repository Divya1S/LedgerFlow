# ADR-001: PostgreSQL as the single source of truth

Status: accepted (Phase 1)

## Context

A payment platform holds exactly one kind of state that can never be
wrong: who has how much money. That state is written concurrently, read
under strict consistency, and audited.

## Decision

All financial state (accounts, balances, transactions, ledger, payments,
idempotency, outbox, audit) lives in one PostgreSQL database. Redis holds
only rebuildable projections; Kafka carries only facts about already
committed state. SQL is written by hand through JdbcClient; no ORM hides
queries or plans.

## Why relational, why PostgreSQL

- The invariants are relational: balanced double-entry postings, foreign
  keys, uniqueness of idempotency keys, balance floors. PostgreSQL
  enforces all of them in the engine (constraints, deferred triggers),
  so application bugs become errors instead of corrupted money.
- Multi-row ACID transactions are the exact tool for "debit A, credit B,
  write audit and outbox, atomically". Document/KV stores make that the
  application's problem.
- Row locks + MVCC give a precise concurrency model we can test against.
- Operationally boring, which is a feature in payments.

## Consequences

- Vertical scaling first; read replicas for read paths; partitioning
  (ADR-008) for table growth. Sharding would be a major redesign and is
  documented as the 1B-transaction answer, not built prematurely.
- Every service boundary that needs financial state talks to PostgreSQL
  or consumes events derived from it, never a second writable store.
