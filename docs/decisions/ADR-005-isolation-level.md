# ADR-005: READ COMMITTED isolation plus explicit row locks

Status: accepted (Phase 3)

## Context

PostgreSQL offers READ COMMITTED (default), REPEATABLE READ and
SERIALIZABLE. Money movements must never double-spend under concurrency.

## Decision

Run at READ COMMITTED and take explicit `SELECT ... FOR UPDATE` locks on
the balance rows, in ascending account id order, before reading balances
for a decision.

## Why not the stricter levels

- SERIALIZABLE detects dangerous interleavings and aborts with 40001,
  turning hot-account contention into retry storms; predicate locking
  costs every transaction. Our writes conflict on known, specific rows,
  so locking exactly those rows is both cheaper and easier to reason
  about.
- REPEATABLE READ prevents non-repeatable reads we do not rely on (every
  decision-relevant read happens after the lock, which always sees the
  latest committed value) and still requires the same explicit locking to
  prevent lost updates.

## Consequences

- Deadlocks are impossible between movements (sorted lock order) and
  handled elsewhere with retries (40P01) for any other path.
- The correctness proof is a test, not an argument: 1000 concurrent
  transfers over one balance leave exact books
  (ConcurrentTransferIT), and the CHECK constraint backstops even a
  future locking bug.
