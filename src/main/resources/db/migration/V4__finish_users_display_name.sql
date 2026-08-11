-- Zero-downtime column rename, step 2 of 2: CONTRACT.
-- Runs only after every instance writes display_name (deployed between V3
-- and V4). On a large production table the NOT NULL would be introduced as
-- ADD CONSTRAINT ... CHECK (display_name IS NOT NULL) NOT VALID followed
-- by VALIDATE CONSTRAINT to avoid a full-table scan under an exclusive
-- lock; the direct ALTER here is acceptable at this table's size and the
-- tradeoff is documented in docs/zero-downtime-migration.md.

ALTER TABLE users ALTER COLUMN display_name SET NOT NULL;

DROP TRIGGER trg_users_sync_name ON users;
DROP FUNCTION sync_user_name();

ALTER TABLE users DROP COLUMN full_name;
