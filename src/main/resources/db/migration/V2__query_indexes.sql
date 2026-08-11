-- ============================================================================
-- V2: indexes driven by measured queries.
--
-- Every index here exists because docs/query-optimization.md shows a real
-- query that needed it, with before/after EXPLAIN ANALYZE from a ~5M row
-- dataset. Indexes on the partitioned parents cascade to every partition,
-- current and future.
-- ============================================================================

-- Account transaction history, keyset paginated:
--   WHERE source_account_id = X [AND (created_at, id) < (cursor)]
--   ORDER BY created_at DESC, id DESC LIMIT n
-- Column order: equality column first (account), then the sort key, so the
-- index yields rows already in page order and the scan stops after n rows.
-- Selectivity: ~1/10k accounts, then time-ordered. Partial: deposits have a
-- NULL source (withdrawals a NULL destination); indexing NULLs would only
-- pay storage for rows these queries can never match.
CREATE INDEX ix_transactions_source_time
    ON transactions (source_account_id, created_at DESC, id DESC)
    WHERE source_account_id IS NOT NULL;

CREATE INDEX ix_transactions_destination_time
    ON transactions (destination_account_id, created_at DESC, id DESC)
    WHERE destination_account_id IS NOT NULL;

-- Account statement / running balance / per-account reconciliation:
--   WHERE account_id = X ORDER BY created_at DESC, id DESC LIMIT n
CREATE INDEX ix_ledger_entries_account_time
    ON ledger_entries (account_id, created_at DESC, id DESC);

-- The zero-sum constraint trigger and entry lookups join on transaction_id.
-- Without this the trigger's per-row sum check is a partition scan, which
-- makes every commit O(partition size).
CREATE INDEX ix_ledger_entries_transaction
    ON ledger_entries (transaction_id);

-- Write overhead accepted: four extra index maintenances per movement
-- (2 transaction indexes are partial, entries hit 2 indexes each). Measured
-- write impact is part of the Phase 8 load test results.
