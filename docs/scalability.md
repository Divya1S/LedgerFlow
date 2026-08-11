# Scalability

What breaks first at each order of magnitude, and the prepared answer.
Current measured baseline: 688 req/s mixed load on one laptop instance
with zero errors (docs/performance.md).

## 10x (millions of transactions/day)

Nothing structural changes:

- **App tier**: stateless by design (JWTs, DB-held idempotency and outbox
  state), so the HPA adds pods. Multiple outbox publishers are already
  safe (`SKIP LOCKED`).
- **PostgreSQL**: a properly sized instance handles this write volume.
  The hot rows (merchant balances) serialize on row locks with
  single-digit-ms critical sections; the measured contention penalty at
  p95 was ~2ms.
- **Reads**: history endpoints move to a read replica behind a separate
  datasource. Replica lag is acceptable there because history is already
  eventually-read (and balances always come from the primary under lock).

## 100x (Stripe-scale writes on hot paths)

Now the primary's write throughput and hot-row serialization matter:

- **Hot accounts**: batch credits (accumulate N pending credits, apply as
  one ledger transaction on a short timer) or shard hot accounts into
  sub-accounts summed at read time. Both preserve double-entry exactly;
  both are localized inside MoneyMovementService because every movement
  already flows through it.
- **Partitioning** already caps per-partition index depth; archival
  detaches old months to cheap storage.
- **Outbox**: switch the poller to CDC (Debezium) for throughput; no
  producer/consumer code changes (ADR-002).
- **Idempotency table churn**: expired-key cleanup by partition drop
  instead of DELETE (same technique as transactions).

## 1B transactions (redesign honestly)

- Shard the ledger by account id across PostgreSQL clusters. Cross-shard
  transfers lose single-database atomicity and need a two-phase
  outbox-driven protocol (debit committed on shard A publishes an event
  that credits on shard B, with reconciliation): this is the point where
  the industry builds custom ledger infrastructure, and pretending a
  single Postgres would do is exactly the fake claim this project avoids.
- Balances become materialized snapshots + recent-entry sums per shard.
- The read side splits fully (CQRS) into a warehouse for analytics.

## What we refuse to do early

Sharding, CQRS and event sourcing are all listed above and all absent
from the codebase on purpose: each multiplies operational and correctness
complexity, and the measured load says the simple design has orders of
magnitude of headroom first (CORRECTNESS > PERFORMANCE, per the project's
priority order).
