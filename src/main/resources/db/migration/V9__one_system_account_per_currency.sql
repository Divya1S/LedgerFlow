-- The platform has one cash account, one fees account and one suspense
-- account per currency, and the code looks them up by (type, currency).
-- Until now nothing stopped a second one from being inserted. That was
-- harmless while the suspense account was unused; with held transfers it
-- would mean held money split across two accounts and a lookup that
-- cannot tell which is the real one.
--
-- So the rule moves into the schema, where the other money rules live.
-- The index is tiny (three rows per currency) and built instantly.

CREATE UNIQUE INDEX ux_accounts_one_system_account_per_currency
    ON accounts (type, currency)
    WHERE user_id IS NULL;
