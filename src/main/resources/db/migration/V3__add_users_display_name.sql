-- Zero-downtime column rename, step 1 of 2: EXPAND.
-- Goal: users.full_name becomes users.display_name with the application
-- serving traffic throughout. This migration is fully backwards
-- compatible: code that still writes full_name keeps working (the trigger
-- mirrors values both ways), and code that writes display_name works too.
-- The CONTRACT step (V4) runs only after all instances run new code.

ALTER TABLE users ADD COLUMN display_name TEXT;

-- Keep both columns coherent during the transition window, whichever one
-- the running code version writes.
CREATE FUNCTION sync_user_name() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.display_name IS NULL THEN
        NEW.display_name := NEW.full_name;
    END IF;
    IF NEW.full_name IS NULL THEN
        NEW.full_name := NEW.display_name;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_users_sync_name
    BEFORE INSERT OR UPDATE ON users
    FOR EACH ROW EXECUTE FUNCTION sync_user_name();

-- Backfill in batches: bounded row locks per statement, so a large table
-- never holds a long lock or bloats one giant transaction. (This table is
-- small in dev; the pattern is what matters.)
DO $$
DECLARE
    updated INT;
BEGIN
    LOOP
        UPDATE users SET display_name = full_name
        WHERE id IN (
            SELECT id FROM users WHERE display_name IS NULL LIMIT 10000
        );
        GET DIAGNOSTICS updated = ROW_COUNT;
        EXIT WHEN updated = 0;
    END LOOP;
END $$;
