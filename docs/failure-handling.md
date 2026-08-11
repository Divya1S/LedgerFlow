# Failure Handling

What happens when each dependency fails, what the user sees, and which test
proves it. The design rule behind all of it: **PostgreSQL commits are the
only definition of truth. Anything else failing may cost speed or
freshness, never correctness.**

## PostgreSQL

| Failure | Behavior |
|---|---|
| Down / unreachable | Money endpoints fail with 5xx. The API never reports success without a commit. |
| Pool exhausted | Hikari fails fast (3s connection timeout) instead of queueing requests into a pileup. |
| Transaction killed mid-flight | Everything rolls back together: movement, ledger entries, balance updates, audit row, outbox row, idempotency claim. There is no partial financial state by construction (single transaction). |
| Deadlock (40P01) / serialization failure (40001) | Retried up to 3 times with backoff outside the transaction boundary; proven in [DeadlockIT](../src/test/java/com/ledgerflow/ledger/DeadlockIT.java). |
| Commit result unknown (timeout during COMMIT) | The client retries with the same Idempotency-Key. If the commit happened, the stored response replays; if it rolled back, the operation re-executes. Either way, no duplicate. |

## Kafka

| Failure | Behavior | Proof |
|---|---|---|
| Broker down | Payments succeed (outbox row commits with the money). Publisher retries with per-row backoff. | [OutboxKafkaIT.kafkaOutage...](../src/test/java/com/ledgerflow/outbox/OutboxKafkaIT.java) pauses the broker |
| Broker recovers | Outbox drains in order; consumers catch up; dedup prevents double effects. | same test |
| Event unpublishable after 10 attempts | Parked as FAILED in the outbox (DB-side dead letter), operator-requeueable. | publisher logic |
| Consumer poison message | 3 retries, then `<topic>.DLT`; partition unblocks. | poison test in same class |

## Redis

| Failure | Behavior | Proof |
|---|---|---|
| Down | Cache reads return miss, writes no-op, rate limiter fails OPEN. A 10s breaker stops per-request connection timeouts. API latency degrades to database speed; results stay correct. | [RedisCacheAndRateLimitIT.redisOutage...](../src/test/java/com/ledgerflow/common/RedisCacheAndRateLimitIT.java) pauses Redis mid-test |
| Recovers | Caching and limiting resume within the breaker window. | same test |
| Stale data risk | First-page history cache is evicted after commit by the movement itself; TTL 30s bounds staleness for any missed eviction; cursored pages never come from cache. | cache eviction test |

Why the rate limiter fails open and not closed: correctness never depended
on it, and failing closed would turn a Redis outage into a full write
outage, which is a worse failure than briefly losing throttling.

A lesson chaos testing taught the hard way: fail-open logic is only as
fast as the client timeout in front of it. The first chaos run showed
requests hanging for up to 25 seconds during the Redis pause, because
Lettuce's default command timeout is 60s and the breaker only trips after
the first exception. The fix is a 500ms Redis command/connect timeout
(application.yml), so the breaker takes over within half a second. The
same run also showed the outbox publisher (100 rows per 500ms poll)
falling 65k events behind a ~1100 movements/s burst; the batch size and
poll interval are now sized for ~5000 events/s per instance, measured, not
guessed.

## Application instance

Stateless by design: sessions are JWTs, idempotency state is in
PostgreSQL, outbox state is in PostgreSQL. Killing an instance mid-request
rolls back its transaction; a retry with the same key lands on another
instance and re-executes safely. Multiple instances can run the outbox
publisher concurrently thanks to `FOR UPDATE SKIP LOCKED`.

## What deliberately does NOT exist

- No compensation/saga logic: nothing needs compensating because nothing
  financial spans more than one database transaction.
- No Redis-based financial state: nothing to rebuild or reconcile.
- No exactly-once delivery claims: consumers are idempotent instead.
