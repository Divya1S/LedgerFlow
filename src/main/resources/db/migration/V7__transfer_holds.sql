-- ============================================================================
-- V7: held transfers
--
-- A transfer that is risky for the sender (a first payment to someone new,
-- above a threshold) is no longer final the instant it is sent. The money
-- leaves the sender's wallet and waits in the platform suspense account
-- until a release time. Until then the sender can cancel and get it back.
-- docs/decisions/ADR-010 has the reasoning and the evidence behind it.
--
-- How the books record it (every step is a balanced transaction):
--
--   HOLD          sender -X, suspense +X      when the transfer is sent
--   HOLD_RELEASE  suspense -X, recipient +X   when the hold ends
--   HOLD_RETURN   suspense -X, sender +X      when the sender cancels
--
-- So money on hold is never "nowhere": it sits in an account, and
--
--   balance(suspense) = sum(amount) of holds with status HELD
--
-- is an invariant anyone can check with one query.
--
-- This migration only adds. The type check on transactions is replaced by
-- a wider one that is NOT VALID here and validated in V8, in its own
-- transaction, so the brief exclusive lock taken by DROP CONSTRAINT is not
-- held for the length of a table scan.
-- ============================================================================

ALTER TABLE transactions DROP CONSTRAINT transactions_type_check;

ALTER TABLE transactions ADD CONSTRAINT transactions_type_check
    CHECK (type IN ('DEPOSIT', 'WITHDRAWAL', 'TRANSFER', 'PAYMENT', 'REFUND',
                    'HOLD', 'HOLD_RELEASE', 'HOLD_RETURN'))
    NOT VALID;

-- ----------------------------------------------------------------------------
-- transfer_holds: one row per held transfer, from sent to settled.
--
-- reasons records why it was held, in codes the dashboard turns into plain
-- words, so the person is never left guessing. release_at is a promise:
-- the runner releases at that time without anyone having to approve it.
-- ----------------------------------------------------------------------------

CREATE TABLE transfer_holds (
    id                            UUID PRIMARY KEY,
    sender_user_id                UUID        NOT NULL REFERENCES users (id),
    recipient_user_id             UUID        NOT NULL REFERENCES users (id),
    source_account_id             UUID        NOT NULL REFERENCES accounts (id),
    destination_account_id        UUID        NOT NULL REFERENCES accounts (id),
    amount                        BIGINT      NOT NULL CHECK (amount > 0),
    currency                      CHAR(3)     NOT NULL,
    description                   TEXT,
    risk_level                    TEXT        NOT NULL CHECK (risk_level IN ('ELEVATED', 'HIGH')),
    reasons                       JSONB       NOT NULL,
    status                        TEXT        NOT NULL DEFAULT 'HELD'
                                  CHECK (status IN ('HELD', 'RELEASED', 'CANCELLED', 'RETURNED')),
    release_at                    TIMESTAMPTZ NOT NULL,
    hold_transaction_id           UUID        NOT NULL,
    hold_transaction_created_at   TIMESTAMPTZ NOT NULL,
    settle_transaction_id         UUID,
    settle_transaction_created_at TIMESTAMPTZ,
    resolved_at                   TIMESTAMPTZ,
    created_at                    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                    TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (hold_transaction_id, hold_transaction_created_at)
        REFERENCES transactions (id, created_at),
    FOREIGN KEY (settle_transaction_id, settle_transaction_created_at)
        REFERENCES transactions (id, created_at),
    CONSTRAINT chk_hold_distinct_accounts CHECK (source_account_id <> destination_account_id),
    -- A hold is open exactly while no settling transaction exists. It can
    -- never say "released" without the entries that moved the money.
    CONSTRAINT chk_hold_settled CHECK ((status = 'HELD') = (settle_transaction_id IS NULL))
);

-- One hold per holding transaction; also the lookup the history list uses.
CREATE UNIQUE INDEX ux_transfer_holds_hold_transaction
    ON transfer_holds (hold_transaction_id);

-- The runner's poll: proportional to holds that are still open.
CREATE INDEX ix_transfer_holds_due
    ON transfer_holds (release_at)
    WHERE status = 'HELD';

-- "My held transfers", newest first.
CREATE INDEX ix_transfer_holds_sender
    ON transfer_holds (sender_user_id, created_at DESC);

-- "Money on its way to me", and the count of strangers paying one
-- recipient in a short time (a sign of a mule account).
CREATE INDEX ix_transfer_holds_recipient
    ON transfer_holds (recipient_user_id, created_at DESC);

CREATE TRIGGER trg_transfer_holds_updated_at
    BEFORE UPDATE ON transfer_holds
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
