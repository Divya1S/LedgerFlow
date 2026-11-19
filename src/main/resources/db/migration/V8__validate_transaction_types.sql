-- Validates the wider type check added in V7. VALIDATE CONSTRAINT scans
-- the table under a lock that still allows reads and writes, and running
-- it as its own migration keeps it out of the transaction that held the
-- exclusive lock for DROP CONSTRAINT.

ALTER TABLE transactions VALIDATE CONSTRAINT transactions_type_check;
