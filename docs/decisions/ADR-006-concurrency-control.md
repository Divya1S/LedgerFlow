# ADR-006: Pessimistic ordered locking for balance updates

Status: accepted (Phase 3)

## Context

Concurrent transfers hitting the same accounts must serialize on the
balance check. Options: optimistic versioning with retry, pessimistic row
locks, serializable isolation (see ADR-005), advisory locks.

## Decision

Pessimistic: one code path (MoneyMovementService) locks each touched
`account_balances` row with `SELECT ... FOR UPDATE`, one query per row,
in ascending account id order, then validates and applies.

## Why

- Hot accounts are the norm in payments (merchants, platform accounts).
  Optimistic concurrency degrades exactly there: nearly every attempt
  conflicts and retries, multiplying load. Lock queues degrade
  gracefully instead; the load test shows contention on one wallet
  costing ~2ms at p95 versus the uncontended path.
- Deterministic lock order makes movement-vs-movement deadlocks
  impossible by construction, verified by a 400-transfer bidirectional
  storm (0 deadlocks) and by a deliberately reversed-order test that
  DOES deadlock to show what the ordering prevents.
- The `version` column still exists on balances for diagnostics and as
  an escape hatch if a future path needs optimistic semantics.

## Consequences

- Throughput per hot account is bounded by critical-section length
  (single-digit ms). At rates where that bound matters, the documented
  next steps are batching credits or sub-ledger sharding of hot
  accounts, not abandoning locks.
