# Performance

Real numbers from real runs. Nothing here is projected or invented.

## Environment (be honest about what this is)

- Apple M3 MacBook, 16 GB RAM
- Application on the host JVM (OpenJDK 21), PostgreSQL 17 / Kafka / Redis
  in Docker Desktop containers on the same machine
- Database preloaded with the 5M-transaction / 9.6M-entry seed dataset,
  so index depths are realistic, not toy-sized
- Load generator (k6) also on the same machine

These numbers compare code paths and prove correctness under load on one
laptop. They are NOT capacity claims for production hardware; network hops,
managed-service latencies and co-located-load distortion all differ.

## Load test (k6, 60s, 53 concurrent VUs, 5 scenarios in parallel)

Command: `k6 run load/ledgerflow-load.js` (per-user rate limit raised for
the test; production default is 30 money requests/min/user).

**44,375 requests, 688 req/s sustained, 0 failed (0.00%), 100% checks.**

| Scenario | What it exercises | med | p90 | p95 | p99 | max |
|---|---|---|---|---|---|---|
| transfers (20 VUs) | full ACID movement: locks, ledger, balances, audit, outbox | 55.5ms | 87.7ms | 103.5ms | 142.0ms | 259.7ms |
| hot (10 VUs) | 10 VUs draining ONE wallet: worst-case row contention | 57.6ms | 89.9ms | 105.1ms | 147.3ms | 269.2ms |
| payments (10 VUs) | movement + payment row + 3-leg fee posting | 82.0ms | 119.6ms | 137.1ms | 179.3ms | 310.6ms |
| history (10 VUs) | first-page reads via Redis cache | 96.5ms | 143.4ms | 163.5ms | 218.2ms | 409.7ms |
| accounts (3 VUs) | register + login + open account (bcrypt-bound) | 118.2ms | 181.7ms | 204.6ms | 238.4ms | 342.9ms |

Observations worth defending in review:

- **Hot-wallet contention costs almost nothing at this rate** (p95 105ms vs
  103ms uncontended). Ordered row locks serialize writers into a short
  queue; each critical section is a few milliseconds, so a 10-deep queue
  adds single-digit milliseconds. Contention would dominate only when
  arrival rate approaches 1/critical-section-time per hot row.
- **The bcrypt scenario is the slowest by design**: password hashing is
  supposed to be expensive. It is CPU-bound and would scale horizontally.
- **History p95 > transfer p95 on this run**: the cache halves are fast,
  but misses pay JSON serialization of 50-row pages while 40 write-VUs
  keep evicting the hot accounts' pages. The cached-hit path measured in
  isolation is far faster (X-Cache: HIT responses).
- Zero errors at 688 req/s includes zero deadlocks (the
  `ledgerflow_db_transient_retries_total` counter stayed 0) because lock
  ordering prevents them by construction.

## Chaos test (120s of transfers while dependencies die)

`./chaos/chaos-test.sh` stops Kafka at t=20s (restart t=45s) and pauses
Redis at t=60s (unpause t=85s) under continuous transfer load, then audits
the books in PostgreSQL.

The FIRST chaos run found two real problems, both fixed and re-measured:

1. Requests hung up to **25.1 seconds** during the Redis pause: the
   fail-open breaker only trips after the first exception, and Lettuce's
   default command timeout is 60s. Fix: 500ms Redis command/connect
   timeouts.
2. The outbox fell **65,012 events behind** a ~1,100 movements/s burst:
   the publisher was capped at 100 rows per 500ms poll (200/s). Fix:
   1,000 rows per 200ms (~5,000/s per instance).

Post-fix run: **102,110 requests, 0 failed (0.00%)** across both outages,
median 9.2ms, p95 21.7ms, **max 533ms** (was 25.1s), and the final audit:

```
global ledger sum:            0 (must be 0)
unbalanced transactions:      0 (must be 0)
balance/ledger mismatches:    0 (must be 0; floor-constrained accounts)
outbox still pending:         0 (should drain to 0)
outbox parked FAILED:         0
== chaos: PASSED, money stayed correct through Kafka and Redis outages ==
```

## Query-level performance

Measured `EXPLAIN ANALYZE` improvements (statement page 403.5ms to 0.48ms,
OFFSET vs keyset 421ms vs 0.86ms, partition pruning) live in
[query-optimization.md](query-optimization.md) with full plans.
