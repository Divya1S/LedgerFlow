-- ============================================================================
-- Analytics query showcase. Run against the seeded dev dataset
-- (scripts/seed-dev-data.sql, ~5M transactions).
-- Each query is production-shaped: parameterizable, index-aware, and
-- explained in docs/sql/README.md.
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 1. Daily payment volume, last 30 days: aggregation + FILTER
-- ----------------------------------------------------------------------------
SELECT date_trunc('day', created_at) AS day,
       count(*)                                          AS payments,
       count(*) FILTER (WHERE status = 'FAILED')         AS failed,
       round(100.0 * count(*) FILTER (WHERE status = 'FAILED') / count(*), 2) AS failed_pct,
       sum(amount) FILTER (WHERE status = 'COMPLETED')   AS completed_volume
FROM transactions
WHERE type = 'PAYMENT'
  AND created_at >= now() - interval '30 days'
GROUP BY 1
ORDER BY 1;

-- ----------------------------------------------------------------------------
-- 2. Top 10 merchants by revenue this month: join + GROUP BY + HAVING + RANK
-- ----------------------------------------------------------------------------
WITH merchant_revenue AS (
    SELECT t.destination_account_id AS merchant_account_id,
           count(*)    AS payment_count,
           sum(t.amount) AS revenue
    FROM transactions t
    WHERE t.type = 'PAYMENT'
      AND t.status = 'COMPLETED'
      AND t.created_at >= date_trunc('month', now())
    GROUP BY t.destination_account_id
    HAVING count(*) >= 5
)
SELECT rank() OVER (ORDER BY r.revenue DESC) AS revenue_rank,
       a.name,
       u.full_name AS owner,
       r.payment_count,
       r.revenue
FROM merchant_revenue r
JOIN accounts a ON a.id = r.merchant_account_id
JOIN users u    ON u.id = a.user_id
ORDER BY r.revenue DESC
LIMIT 10;

-- ----------------------------------------------------------------------------
-- 3. Running balance for one account: SUM OVER (window function)
--    (The API computes this backwards from the stored balance; this is the
--    audit/reporting form over a date range.)
-- ----------------------------------------------------------------------------
SELECT e.created_at,
       e.transaction_id,
       e.amount,
       sum(e.amount) OVER (ORDER BY e.created_at, e.id
                           ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) AS running_balance
FROM ledger_entries e
WHERE e.account_id = :account_id
  AND e.created_at >= now() - interval '90 days'
ORDER BY e.created_at, e.id;

-- ----------------------------------------------------------------------------
-- 4. Month-over-month platform volume growth: CTE + LAG
-- ----------------------------------------------------------------------------
WITH monthly AS (
    SELECT date_trunc('month', created_at) AS month,
           sum(amount) AS volume,
           count(*)    AS transactions
    FROM transactions
    WHERE status = 'COMPLETED'
    GROUP BY 1
)
SELECT month,
       volume,
       transactions,
       lag(volume) OVER (ORDER BY month)                       AS previous_volume,
       round(100.0 * (volume - lag(volume) OVER (ORDER BY month))
             / NULLIF(lag(volume) OVER (ORDER BY month), 0), 1) AS growth_pct
FROM monthly
ORDER BY month;

-- ----------------------------------------------------------------------------
-- 5. Suspicious velocity: accounts sending 5+ transfers inside any
--    10 minute window (LAG over per-account time series)
-- ----------------------------------------------------------------------------
WITH ordered AS (
    SELECT source_account_id,
           created_at,
           lag(created_at, 4) OVER (PARTITION BY source_account_id ORDER BY created_at) AS fifth_back
    FROM transactions
    WHERE type = 'TRANSFER'
      AND status = 'COMPLETED'
      AND created_at >= now() - interval '7 days'
)
SELECT source_account_id,
       count(*) AS burst_windows,
       min(created_at) AS first_burst_at
FROM ordered
WHERE fifth_back IS NOT NULL
  AND created_at - fifth_back <= interval '10 minutes'
GROUP BY source_account_id
ORDER BY burst_windows DESC
LIMIT 20;

-- ----------------------------------------------------------------------------
-- 6. Each user's 3 largest transactions: ROW_NUMBER + PARTITION BY
-- ----------------------------------------------------------------------------
WITH ranked AS (
    SELECT a.user_id,
           t.id AS transaction_id,
           t.amount,
           t.created_at,
           row_number() OVER (PARTITION BY a.user_id ORDER BY t.amount DESC) AS rn
    FROM transactions t
    JOIN accounts a ON a.id = t.source_account_id
    WHERE t.status = 'COMPLETED'
      AND t.created_at >= now() - interval '30 days'
)
SELECT user_id, transaction_id, amount, created_at
FROM ranked
WHERE rn <= 3;

-- ----------------------------------------------------------------------------
-- 7. Refund rate per merchant: LEFT JOIN + conditional aggregation
-- ----------------------------------------------------------------------------
SELECT a.id   AS merchant_account_id,
       a.name,
       count(p.id)                                        AS payments,
       count(r.id)                                        AS refunds,
       coalesce(sum(r.amount), 0)                         AS refunded_volume,
       round(100.0 * count(r.id) / NULLIF(count(p.id), 0), 2) AS refund_rate_pct
FROM accounts a
LEFT JOIN payments p ON p.destination_account_id = a.id AND p.status <> 'FAILED'
LEFT JOIN refunds  r ON r.payment_id = p.id AND r.status = 'COMPLETED'
WHERE a.type = 'MERCHANT'
GROUP BY a.id, a.name
HAVING count(p.id) > 0
ORDER BY refund_rate_pct DESC
LIMIT 20;

-- ----------------------------------------------------------------------------
-- 8. Dormant accounts reactivating with a large movement: LEAD across gaps
-- ----------------------------------------------------------------------------
WITH activity AS (
    SELECT source_account_id AS account_id,
           created_at,
           amount,
           lead(created_at) OVER (PARTITION BY source_account_id ORDER BY created_at) AS next_at,
           lead(amount)     OVER (PARTITION BY source_account_id ORDER BY created_at) AS next_amount
    FROM transactions
    WHERE status = 'COMPLETED' AND source_account_id IS NOT NULL
)
SELECT account_id,
       created_at   AS last_active_before_gap,
       next_at      AS reactivated_at,
       next_at - created_at AS dormant_for,
       next_amount  AS reactivation_amount
FROM activity
WHERE next_at - created_at >= interval '60 days'
  AND next_amount >= 50000
ORDER BY next_amount DESC
LIMIT 20;
