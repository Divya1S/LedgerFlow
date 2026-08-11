-- Development seed: about 5 million transactions and 9.6 million ledger
-- entries across 7 months, for query optimization work with realistic
-- volume. DEV ONLY, never for production.
--
-- Notes:
--  * session_replication_role = replica disables triggers and FK checks for
--    the bulk load (standard bulk-load technique). The generated data is
--    balanced by construction: every COMPLETED transaction gets exactly one
--    -amount and one +amount entry.
--  * Seeded wallets get min_balance NULL because random histories do not
--    respect balance floors. Accounts created through the API keep floor 0.
--  * gen_random_uuid() (v4) is fine here: pagination orders by
--    (created_at, id), so id is only a tiebreaker.

\set ON_ERROR_STOP on
\timing on

SET session_replication_role = replica;
SELECT setseed(0.42);

-- ---------------------------------------------------------------------------
-- Partitions for the seed months (past months do not exist yet)
-- ---------------------------------------------------------------------------
DO $$
DECLARE
    month_start date;
    month_end   date;
    suffix      text;
BEGIN
    FOR i IN 0..7 LOOP
        month_start := date_trunc('month', DATE '2026-02-01') + make_interval(months => i);
        month_end   := month_start + interval '1 month';
        suffix      := to_char(month_start, 'YYYY_MM');
        -- Bounds as explicit UTC instants, matching create_month_partitions.
        EXECUTE format('CREATE TABLE IF NOT EXISTS transactions_%s PARTITION OF transactions FOR VALUES FROM (%L) TO (%L)',
                       suffix, month_start || ' 00:00:00+00', month_end || ' 00:00:00+00');
        EXECUTE format('CREATE TABLE IF NOT EXISTS ledger_entries_%s PARTITION OF ledger_entries FOR VALUES FROM (%L) TO (%L)',
                       suffix, month_start || ' 00:00:00+00', month_end || ' 00:00:00+00');
        IF NOT EXISTS (SELECT 1 FROM pg_trigger WHERE tgname = 'trg_ledger_balanced_' || suffix) THEN
            EXECUTE format('CREATE CONSTRAINT TRIGGER trg_ledger_balanced_%s AFTER INSERT ON ledger_entries_%s DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION assert_transaction_balanced()',
                           suffix, suffix);
        END IF;
    END LOOP;
END $$;

-- ---------------------------------------------------------------------------
-- Users and accounts
-- ---------------------------------------------------------------------------
CREATE TEMP TABLE seed_users AS
SELECT gen_random_uuid() AS id, i
FROM generate_series(1, 5000) i;

INSERT INTO users (id, email, password_hash, full_name, role, status)
SELECT id,
       'seed-user-' || i || '@seed.ledgerflow.io',
       '$2a$10$seedseedseedseedseedsePlaceholderHashNotALoginXXXXXXX',
       'Seed User ' || i,
       'USER', 'ACTIVE'
FROM seed_users;

CREATE TEMP TABLE seed_wallets AS
SELECT gen_random_uuid() AS id, u.id AS user_id, u.i AS idx
FROM seed_users u;

CREATE TEMP TABLE seed_merchants AS
SELECT gen_random_uuid() AS id, u.id AS user_id, u.i AS idx
FROM seed_users u
WHERE u.i <= 500;

INSERT INTO accounts (id, user_id, type, currency, status, name)
SELECT id, user_id, 'USER_WALLET', 'USD', 'ACTIVE', 'Seed wallet'
FROM seed_wallets;

INSERT INTO accounts (id, user_id, type, currency, status, name)
SELECT id, user_id, 'MERCHANT', 'USD', 'ACTIVE', 'Seed merchant'
FROM seed_merchants;

INSERT INTO account_balances (account_id, balance, min_balance)
SELECT id, 0, NULL::bigint FROM seed_wallets
UNION ALL
SELECT id, 0, NULL::bigint FROM seed_merchants;

-- ---------------------------------------------------------------------------
-- Transactions and ledger entries, month by month
-- ---------------------------------------------------------------------------
DO $$
DECLARE
    wallet_ids   uuid[];
    merchant_ids uuid[];
    cash_id      uuid;
    month_start  timestamptz;
    per_month    int := 715000;
BEGIN
    SELECT array_agg(id ORDER BY idx) INTO wallet_ids FROM seed_wallets;
    SELECT array_agg(id ORDER BY idx) INTO merchant_ids FROM seed_merchants;
    SELECT id INTO cash_id FROM accounts WHERE type = 'SYSTEM_CASH' AND currency = 'USD';

    FOR i IN 0..6 LOOP
        month_start := DATE '2026-02-01' + make_interval(months => i);
        RAISE NOTICE 'seeding month % ...', month_start::date;

        INSERT INTO transactions (id, created_at, type, status, amount, currency,
                                  source_account_id, destination_account_id, description)
        SELECT g.id,
               g.created_at,
               g.type,
               g.status,
               g.amount,
               'USD',
               CASE g.type WHEN 'DEPOSIT' THEN cash_id
                           ELSE wallet_ids[1 + floor(g.rs * 5000)::int] END,
               CASE g.type WHEN 'TRANSFER'   THEN wallet_ids[1 + floor(g.rd * 5000)::int]
                           WHEN 'DEPOSIT'    THEN wallet_ids[1 + floor(g.rd * 5000)::int]
                           WHEN 'PAYMENT'    THEN merchant_ids[1 + floor(g.rd * 500)::int]
                           WHEN 'WITHDRAWAL' THEN cash_id END,
               'seed'
        FROM (
            SELECT gen_random_uuid() AS id,
                   month_start + random() * interval '2591000 seconds' AS created_at,
                   CASE WHEN r < 0.60 THEN 'TRANSFER'
                        WHEN r < 0.75 THEN 'DEPOSIT'
                        WHEN r < 0.90 THEN 'PAYMENT'
                        ELSE 'WITHDRAWAL' END AS type,
                   CASE WHEN rstat < 0.96 THEN 'COMPLETED' ELSE 'FAILED' END AS status,
                   (100 + floor(ra * 99900))::bigint AS amount,
                   rs, rd
            FROM (
                SELECT random() AS r, random() AS rs, random() AS rd,
                       random() AS ra, random() AS rstat
                FROM generate_series(1, per_month)
            ) raw
        ) g
        WHERE NOT (g.type = 'TRANSFER' AND g.rs = g.rd);

        INSERT INTO ledger_entries (id, transaction_id, created_at, account_id, amount, currency)
        SELECT gen_random_uuid(), t.id, t.created_at, v.acct, v.amt, 'USD'
        FROM transactions t
        CROSS JOIN LATERAL (VALUES (t.source_account_id, -t.amount),
                                   (t.destination_account_id, t.amount)) v(acct, amt)
        WHERE t.created_at >= month_start
          AND t.created_at < month_start + interval '1 month'
          AND t.status = 'COMPLETED'
          AND t.description = 'seed';
    END LOOP;
END $$;

-- ---------------------------------------------------------------------------
-- Make stored balances equal ledger sums, then clean up
-- ---------------------------------------------------------------------------
UPDATE account_balances b
SET balance = s.ledger_sum
FROM (SELECT account_id, sum(amount) AS ledger_sum FROM ledger_entries GROUP BY account_id) s
WHERE s.account_id = b.account_id;

SET session_replication_role = DEFAULT;

VACUUM ANALYZE transactions;
VACUUM ANALYZE ledger_entries;
VACUUM ANALYZE accounts;
VACUUM ANALYZE account_balances;

SELECT (SELECT count(*) FROM transactions)   AS transactions,
       (SELECT count(*) FROM ledger_entries) AS ledger_entries,
       (SELECT coalesce(sum(amount), -1) FROM ledger_entries) AS global_ledger_sum;
