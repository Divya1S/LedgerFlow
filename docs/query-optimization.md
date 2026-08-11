# Query Optimization

Real measurements on this project's dataset. Nothing here is estimated or
invented: every number comes from `EXPLAIN (ANALYZE, BUFFERS)` runs against
the seeded development database.

**Environment.** PostgreSQL 17.10 in Docker on an Apple Silicon MacBook
(Docker Desktop). Dataset from [scripts/seed-dev-data.sql](../scripts/seed-dev-data.sql):
5,005,000 transactions and 9,609,434 ledger entries across 7 monthly
partitions, 5,000 user wallets, 500 merchants. Numbers are from warm-cache
runs (each query executed twice, second run reported) unless noted.
Laptop numbers are for comparing plans, not for capacity planning.

The indexes discussed here ship in
[V2__query_indexes.sql](../src/main/resources/db/migration/V2__query_indexes.sql).
Baseline numbers were measured on the same data with only V1 (constraint
indexes) present.

## Case 1: account transaction history

The busiest wallet has 1,737 transactions. The API query unions the source
side and destination side (each side has its own index after V2) and takes
the newest 50.

| | Plan | Execution time |
|---|---|---|
| Before | Parallel seq scan of all 11 partitions for each UNION branch | **201.4 ms** |
| After | Bitmap/index scans on `(source/destination_account_id, created_at DESC, id DESC)` per partition | **14.9 ms** |

13x faster. The remaining cost is fetching all of the account's rows before
sorting: the UNION + DISTINCT ON shape cannot terminate early across
partitions. That is acceptable at 1,737 rows per account; if accounts grew
100x hotter, the next step would be a per-branch `LIMIT` pushdown (top-50
from each branch before the merge), which this query shape supports.

## Case 2: account statement page (ledger entries)

`WHERE account_id = X ORDER BY created_at DESC, id DESC LIMIT 50` against
9.6M rows.

Before (V1 only), the whole table is scanned and top-N sorted:

```
Limit (actual time=399.124..402.737 rows=50)
  Buffers: shared hit=7032 read=121187        <- 1 GB of pages touched
  -> Gather Merge (Workers: 2)
     -> Sort (top-N heapsort)
        -> Parallel Seq Scan on every ledger_entries partition
Execution Time: 403.5 ms
```

After `ix_ledger_entries_account_time (account_id, created_at DESC, id DESC)`:

```
Limit (actual time=0.040..0.448 rows=50)
  Buffers: shared hit=60                      <- 60 pages
  -> Append
     -> Index Scan using ..._account_id_created_at_id_idx on each partition
        (newest partitions first, stops after 50 rows)
Execution Time: 0.48 ms
```

**403.5 ms to 0.48 ms (840x), and 121K page reads to 60.** The index
matches the query exactly: equality column first, then the sort key in
index order, so PostgreSQL walks the index in output order and stops at 50.

## Case 3: partition pruning

Monthly aggregate over `transactions`, `status = 'COMPLETED'`:

| Query | Partitions scanned | Execution time |
|---|---|---|
| One month window (`created_at >= '2026-05-01' AND < '2026-06-01'`) | 1 of 11 | **44.0 ms** |
| No time filter | all 11 | **277.1 ms** |

The plan for the windowed query shows only `transactions_2026_05` under the
aggregate: the planner proved every other partition irrelevant from the
range bounds and never opened them. This is the payoff of partitioning by
`created_at`: time-windowed queries (which is most of them, in a ledger) do
work proportional to the window, not to table lifetime, and archival is
`DETACH PARTITION` instead of a multi-hour `DELETE`.

A hard-won detail: partition bounds are created as explicit UTC instants
(`FOR VALUES FROM ('2026-08-01 00:00:00+00')`). Bare date literals in
partition DDL are interpreted in the creating session's TimeZone; during
development, partitions created from an app session (US Pacific) and a psql
session (UTC) ended up with misaligned bounds and a 7 hour hole between two
months, which rejected inserts. See `create_month_partitions()` in V1.

## Case 4: OFFSET vs keyset pagination

Deep pagination through SYSTEM_CASH's history (1.25M rows as source),
page at position 500,000, both queries fully indexed:

OFFSET:

```
Limit (actual time=393.373..398.345 rows=50)
  Buffers: shared hit=5985 read=85106, temp read=3028 written=3875
  -> Gather Merge (rows=500050)               <- produced and threw away
     ...                                         half a million rows
Execution Time: 421.4 ms
```

Keyset (`AND (created_at, id) < (cursor) ... LIMIT 50`):

```
Limit (actual time=0.261..0.796 rows=50)
  Buffers: shared hit=19 read=53
  -> Append
     -> Index Scan ... Index Cond: ((source_account_id = ...) AND
        (ROW(created_at, id) < ROW('2026-04-11 ...', '...')))
Execution Time: 0.86 ms
```

**421 ms vs 0.86 ms at depth 500K (490x).** OFFSET must generate and
discard every skipped row, so cost grows linearly with depth (and here it
even spilled to temp files). The keyset predicate is just another index
condition, so every page costs the same as page one. OFFSET also skips or
repeats rows when data changes between pages; a keyset cursor is stable.
This is why the API paginates exclusively with cursors
([TransactionQueryController](../src/main/java/com/ledgerflow/transactionquery/api/TransactionQueryController.java)).

## Index design summary

| Index | Serves | Why this shape |
|---|---|---|
| `(source_account_id, created_at DESC, id DESC) WHERE source_account_id IS NOT NULL` | history keyset, source side | equality column, then sort key; partial because deposits have NULL source and can never match |
| `(destination_account_id, created_at DESC, id DESC) WHERE ... IS NOT NULL` | history keyset, destination side | same, mirrored |
| `ledger_entries (account_id, created_at DESC, id DESC)` | statements, running balance, per-account reconciliation | Case 2 |
| `ledger_entries (transaction_id)` | zero-sum constraint trigger, entry lookups | without it, every commit's trigger check scans a partition |

Deliberately NOT indexed: `transactions.status` alone (3 values, no
selectivity), `amount` (no query filters on it), and anything on
`account_balances` beyond the PK (the hot row must stay narrow and its
updates HOT-eligible).

Every additional index costs write amplification on the money path. The
four above earn it; measured write throughput including them is part of the
Phase 8 load test results.
