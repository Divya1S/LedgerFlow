-- ============================================================================
-- V6: product features
--
-- Saved recipients, scheduled transfers, money requests, a readable
-- notification inbox, a working dispute flow, merchant categories for
-- spending insights, and the indexes the new list screens read from.
--
-- Everything here is additive: new tables, new nullable columns, new
-- indexes. Code from before this migration keeps working against the new
-- schema, so it can be applied while the previous version still serves
-- traffic (same expand-first rule as docs/zero-downtime-migration.md).
--
-- The plain CREATE INDEX statements on existing tables are fine at this
-- size. On a large, busy table each would be built with CREATE INDEX
-- CONCURRENTLY in its own non-transactional migration instead.
-- ============================================================================

-- ----------------------------------------------------------------------------
-- accounts.category: what a merchant sells. Drives "spending by category".
-- NOT VALID + VALIDATE keeps the lock on accounts short: the validation
-- scan runs without blocking writes.
-- ----------------------------------------------------------------------------

ALTER TABLE accounts ADD COLUMN category TEXT;

ALTER TABLE accounts ADD CONSTRAINT chk_accounts_category
    CHECK (category IS NULL OR category IN (
        'GROCERIES', 'DINING', 'SHOPPING', 'TRAVEL', 'TRANSPORT',
        'ENTERTAINMENT', 'UTILITIES', 'HEALTH', 'SERVICES', 'OTHER'))
    NOT VALID;

ALTER TABLE accounts VALIDATE CONSTRAINT chk_accounts_category;

-- ----------------------------------------------------------------------------
-- recipients: accounts a user has saved to send money to again.
-- The unique constraint doubles as the "list my recipients" index.
-- ----------------------------------------------------------------------------

CREATE TABLE recipients (
    id            UUID PRIMARY KEY,
    owner_user_id UUID        NOT NULL REFERENCES users (id),
    account_id    UUID        NOT NULL REFERENCES accounts (id),
    nickname      TEXT        NOT NULL CHECK (length(nickname) BETWEEN 1 AND 80),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ux_recipients_owner_account UNIQUE (owner_user_id, account_id)
);

-- ----------------------------------------------------------------------------
-- scheduled_transfers: a transfer to run later, once or on a repeat.
--
-- next_run_at always equals start_at plus next_occurrence periods, so a
-- monthly schedule that starts on the 31st stays anchored to month ends
-- instead of drifting to the 28th. The runner claims due rows with
-- FOR UPDATE SKIP LOCKED; the partial index keeps that poll proportional
-- to the number of due schedules, not to every schedule ever created.
-- ----------------------------------------------------------------------------

CREATE TABLE scheduled_transfers (
    id                     UUID PRIMARY KEY,
    user_id                UUID        NOT NULL REFERENCES users (id),
    source_account_id      UUID        NOT NULL REFERENCES accounts (id),
    destination_account_id UUID        NOT NULL REFERENCES accounts (id),
    amount                 BIGINT      NOT NULL CHECK (amount > 0),
    currency               CHAR(3)     NOT NULL,
    description            TEXT,
    frequency              TEXT        NOT NULL CHECK (frequency IN ('ONCE', 'WEEKLY', 'MONTHLY')),
    start_at               TIMESTAMPTZ NOT NULL,
    next_occurrence        INT         NOT NULL DEFAULT 0,
    next_run_at            TIMESTAMPTZ,
    status                 TEXT        NOT NULL DEFAULT 'ACTIVE'
                           CHECK (status IN ('ACTIVE', 'PAUSED', 'COMPLETED', 'FAILED', 'CANCELLED')),
    runs_completed         INT         NOT NULL DEFAULT 0,
    last_run_at            TIMESTAMPTZ,
    last_outcome           TEXT,
    last_transaction_id    UUID,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_scheduled_distinct_accounts CHECK (source_account_id <> destination_account_id),
    -- An active schedule always knows when it runs next.
    CONSTRAINT chk_scheduled_active_has_next CHECK (status <> 'ACTIVE' OR next_run_at IS NOT NULL)
);

CREATE INDEX ix_scheduled_transfers_due
    ON scheduled_transfers (next_run_at)
    WHERE status = 'ACTIVE';

CREATE INDEX ix_scheduled_transfers_user
    ON scheduled_transfers (user_id, created_at DESC);

CREATE TRIGGER trg_scheduled_transfers_updated_at
    BEFORE UPDATE ON scheduled_transfers
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- ----------------------------------------------------------------------------
-- payment_requests: "please send me this amount", shared as a link.
-- Paying one is an ordinary transfer; the row lock on the request is what
-- stops two people (or one double click) from paying it twice.
-- ----------------------------------------------------------------------------

CREATE TABLE payment_requests (
    id                     UUID PRIMARY KEY,
    requester_user_id      UUID        NOT NULL REFERENCES users (id),
    destination_account_id UUID        NOT NULL REFERENCES accounts (id),
    amount                 BIGINT      NOT NULL CHECK (amount > 0),
    currency               CHAR(3)     NOT NULL,
    note                   TEXT,
    status                 TEXT        NOT NULL DEFAULT 'PENDING'
                           CHECK (status IN ('PENDING', 'PAID', 'CANCELLED')),
    payer_user_id          UUID        REFERENCES users (id),
    transaction_id         UUID,
    transaction_created_at TIMESTAMPTZ,
    expires_at             TIMESTAMPTZ NOT NULL,
    paid_at                TIMESTAMPTZ,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (transaction_id, transaction_created_at) REFERENCES transactions (id, created_at),
    -- A request is paid exactly when it points at the transaction that paid it.
    CONSTRAINT chk_payment_request_paid CHECK ((status = 'PAID') = (transaction_id IS NOT NULL))
);

CREATE INDEX ix_payment_requests_requester
    ON payment_requests (requester_user_id, created_at DESC);

CREATE TRIGGER trg_payment_requests_updated_at
    BEFORE UPDATE ON payment_requests
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- ----------------------------------------------------------------------------
-- notifications: text a person can read, plus read state.
-- status stays the delivery state for a future email/SMS sender; read_at
-- is the inbox state. Older rows have no title and are worded from their
-- type when listed.
-- ----------------------------------------------------------------------------

ALTER TABLE notifications ADD COLUMN title TEXT;
ALTER TABLE notifications ADD COLUMN body TEXT;
ALTER TABLE notifications ADD COLUMN read_at TIMESTAMPTZ;

CREATE INDEX ix_notifications_unread
    ON notifications (user_id)
    WHERE read_at IS NULL;

-- ----------------------------------------------------------------------------
-- disputes: the merchant's reply, the final note, and who decided.
-- The partial unique index allows one open dispute per payment while
-- keeping resolved ones as history.
-- ----------------------------------------------------------------------------

ALTER TABLE disputes ADD COLUMN merchant_response TEXT;
ALTER TABLE disputes ADD COLUMN resolution_note TEXT;
ALTER TABLE disputes ADD COLUMN resolved_by UUID REFERENCES users (id);

CREATE UNIQUE INDEX ux_disputes_one_active_per_payment
    ON disputes (payment_id)
    WHERE status IN ('OPEN', 'UNDER_REVIEW');

CREATE INDEX ix_disputes_opened_by
    ON disputes (opened_by, created_at DESC);

-- ----------------------------------------------------------------------------
-- Indexes for the new list screens.
-- ----------------------------------------------------------------------------

-- "Payments received" for a merchant account, newest first.
CREATE INDEX ix_payments_destination
    ON payments (destination_account_id, created_at DESC, id DESC);

-- A user's own activity feed, newest first, keyset paginated.
CREATE INDEX ix_audit_logs_actor
    ON audit_logs (actor_user_id, occurred_at DESC, id DESC)
    WHERE actor_user_id IS NOT NULL;
