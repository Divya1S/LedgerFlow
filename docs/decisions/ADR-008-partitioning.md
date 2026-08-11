# ADR-008: Monthly range partitioning of transactions and ledger entries

Status: accepted (Phase 1, bounds hardened in Phase 4)

## Context

`transactions` and `ledger_entries` are append-only, unbounded, and almost
always filtered by time window. At 10M+ rows, maintenance (vacuum,
indexes, archival) on monoliths gets painful; deletes for archival are
prohibitively expensive.

## Decision

Both tables are declaratively partitioned by `RANGE (created_at)` per
month from day one. Partitions are pre-created ahead of time by
`create_month_partitions()`; there is deliberately no DEFAULT partition
(fail loudly instead of silently absorbing mis-ranged rows). Bounds are
explicit UTC instants after a real bug where app-session and psql-session
timezones produced misaligned bounds with a 7-hour insert-rejecting gap.

## Evidence

Partition pruning measured: a one-month aggregate scans 1 of 11
partitions in 44ms vs 277ms for the unfiltered scan
(docs/query-optimization.md, Case 3).

## Consequences

- PKs must include the partition key: `(id, created_at)`; uniqueness of
  id alone is by construction (UUIDv7).
- The zero-sum constraint trigger attaches per partition (PostgreSQL
  cannot put constraint triggers on the parent), handled by the same
  maintenance function.
- Archival = `DETACH PARTITION` + export; documented, not yet automated.
