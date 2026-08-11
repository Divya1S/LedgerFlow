# SQL Showcase

Production-shaped analytical queries over the LedgerFlow schema, all
verified to run against the seeded development dataset (5M transactions,
9.6M ledger entries; see [scripts/seed-dev-data.sql](../../scripts/seed-dev-data.sql)).

Files:

- [analytics.sql](analytics.sql): the queries below.
- [../query-optimization.md](../query-optimization.md): measured
  before/after plans for the operational queries the API runs.

| # | Query | Techniques | Notes |
|---|---|---|---|
| 1 | Daily payment volume + failure rate | GROUP BY, `count(*) FILTER` | time filter prunes to 1-2 partitions |
| 2 | Top merchants by revenue | CTE, JOIN, HAVING, `RANK() OVER` | rank window over aggregated CTE |
| 3 | Running account balance | `SUM() OVER (ORDER BY ... ROWS ...)` | audit/report form; the API computes statements backwards from the stored balance instead (see TransactionQueryRepository.statement) |
| 4 | Month-over-month growth | CTE, `LAG() OVER` | growth percent vs previous month |
| 5 | Burst velocity (5+ transfers in 10 min) | `LAG(col, 4) OVER (PARTITION BY ...)` | the offset-lag trick: compare each row to the 4th before it |
| 6 | Top 3 transactions per user | `ROW_NUMBER() OVER (PARTITION BY ...)` | classic top-N per group |
| 7 | Refund rate per merchant | LEFT JOIN chain, conditional aggregation | runs on API-created payments (the bulk seed writes ledger history, not payment lifecycle rows) |
| 8 | Dormant account reactivation | `LEAD() OVER` across time gaps | fraud-flavored gap analysis |

Conventions used across the project's SQL:

- Every query is parameterized; no value is ever concatenated into SQL.
- Time filters use half-open ranges (`>= start AND < end`) so rows on the
  boundary are counted exactly once and partitions prune correctly.
- Aggregation prefers `FILTER (WHERE ...)` over `CASE WHEN` inside
  aggregates for readability.
- Money is `BIGINT` minor units; division for percentages happens at the
  end, on aggregates, in numeric context.
