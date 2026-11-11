# Product features

What was added on top of the core ledger, why each feature was chosen, and
the rule each one has to keep. The core (accounts, transfers, payments,
refunds, the fraud check) is covered in
[engineering-overview.md](engineering-overview.md).

## How the list was chosen

The features came from looking at what comparable products ship: Stripe
Dashboard, Wise, Mercury, Revolut Business, PayPal, Square, Ramp, Brex,
Modern Treasury and Adyen. The ones that kept appearing and fit a wallet
and payments product were built. The ones that did not fit, or were too
large to do properly here, are listed at the end with the reason.

| Feature | Seen in | Endpoint |
|---|---|---|
| Statement export (CSV) | Wise, Mercury, PayPal, Stripe, Adyen, Square | `GET /accounts/{id}/statement/export` |
| Money requests by link | Stripe, Square, Revolut, Wise, PayPal | `/payment-requests` |
| Freeze an account | Wise, Revolut, Ramp, Brex | `POST /accounts/{id}/freeze` |
| Disputes | Stripe, PayPal, Adyen, Square | `/payments/{id}/disputes`, `/disputes` |
| Activity log for the user | Stripe, Brex, Modern Treasury | `GET /activity` |
| Scheduled and repeating transfers | Wise, Mercury, Revolut | `/scheduled-transfers` |
| Saved recipients, name check before paying | Wise, Mercury, Revolut | `/recipients`, `GET /accounts/lookup/{id}` |
| Spending by category, money in and out | Mercury, Wise, Revolut | `GET /insights/overview` |
| Overview with charts | Stripe, Mercury | `GET /insights/overview` |
| Search and filters | Stripe, Mercury | `GET /transactions?...` |
| Notification inbox | Stripe, Square | `/notifications` |
| Profile and password | all | `/me` |

All paths are under `/api/v1`. Swagger UI at `/swagger-ui.html` lists every
request and response, grouped by these names.

## The rules each feature keeps

### Scheduled transfers: each date runs exactly once

A schedule is a row in `scheduled_transfers`. A runner polls for rows that
are due. For each one, in a single database transaction, it:

1. locks the schedule row (`FOR UPDATE SKIP LOCKED`),
2. makes the transfer through the same `TransferService` a person uses,
3. moves the schedule to its next date.

A crash before the commit undoes all three. After the commit the schedule
already points at the next date. There is no moment where the money moved
but the schedule still looks due. `SKIP LOCKED` means several app
instances can poll at once: `ScheduledTransferIT` starts six runners on
one due schedule and checks that one transfer exists afterwards.

A transfer that is refused (not enough money, frozen account) is not
retried in a loop. It rolls back to a savepoint, the refusal is written to
the schedule row in the same outer transaction, and the owner gets a
notification. A repeating schedule waits for its next date.

Dates are counted from the first run, not from the previous one, so a
monthly schedule that starts on the 31st runs on Jan 31, Feb 28, Mar 31.
After downtime, dates that already passed are skipped: one late run, not a
burst of catch-up transfers.

### Money requests: paid once, by one person

Paying a request locks its row, checks it is still pending, makes an
ordinary transfer and marks it paid, all in the idempotent endpoint's
transaction. `PaymentRequestIT` has two people pay the same link at the
same moment: one gets `201`, the other `409`, and the requester receives
the amount once. A CHECK constraint ties status `PAID` to a transaction
id, so a request cannot claim to be paid without the transfer that paid it.

The person paying sees who is asking, the amount and the note. They never
see which account the money lands in, and the endpoint checks ownership
of the source account before it loads the request so that error messages
cannot be used to find out.

### Disputes: the status and the money cannot disagree

```
OPEN          merchant refunds    ->  RESOLVED_CUSTOMER
OPEN          merchant declines   ->  UNDER_REVIEW
UNDER_REVIEW  platform refunds    ->  RESOLVED_CUSTOMER
UNDER_REVIEW  platform declines   ->  RESOLVED_MERCHANT
```

A merchant can always give the money back but cannot close a dispute in
its own favour: declining hands the decision to a platform admin. A
decision for the customer refunds whatever has not been refunded yet,
through the normal refund path, in the same transaction as the status
change. If the merchant cannot cover the refund, the whole thing rolls
back and the dispute stays open (`DisputeIT`). Decisions need an
`Idempotency-Key`, so a retried click does not refund twice. A partial
unique index allows one open dispute per payment.

### Freeze: stops money leaving, not arriving

`MoneyMovementService` reads the account status together with the locked
balance row. `ACTIVE` moves money both ways, `FROZEN` only receives,
`CLOSED` does neither. A refund or a friend's transfer still lands in a
frozen account; a transfer, withdrawal, payment or scheduled run out of it
is refused with `ACCOUNT_FROZEN`.

### Name check before paying

`GET /accounts/lookup/{id}` answers "who is behind this account id" with a
display name, the account type and nothing else. A wallet shows its
owner's name, never the owner's private label for it. A merchant account
shows its business name. Platform accounts are not found. One SQL
expression (`PartyLabel`) produces these names for every list, so the same
account never reads differently on two screens.

### Insights come from the ledger

Money in, money out, the daily chart and spending by category are all
computed from `ledger_entries`, the same rows balances come from. The
closing balance for each day is walked backwards from today's real
balance. Moves between two of the user's own accounts are counted as
neither income nor spending. `SearchAndInsightsIT` checks the totals
against a small known history.

Spending is grouped by what the merchant sells. That category is chosen
when a merchant account is opened and stored on `accounts.category`.

Days are whole days in the viewer's time zone. Only region names are
accepted (`Europe/Paris`, not `+01:00`), because PostgreSQL reads raw
offsets with the sign flipped.

### Exports are consistent and safe to open

A CSV statement needs three reads: the current balance, everything after
the range, and the range itself. They run in one `REPEATABLE READ`
snapshot, otherwise a payment landing between two of them would make the
running balance column disagree with the rows above it.

Text a person typed is defused before it is written: a description that
starts with `=`, `+`, `-` or `@` would run as a formula when the file is
opened in Excel or Sheets, so it is exported as text. A range is limited
to 366 days and 10,000 rows.

### Search keeps the fast path

Filters (type, direction, account, dates, text, amount) are pushed into
both branches of the existing source/destination query, so each branch
still walks its `(account, created_at DESC)` index and stops after a page.
Names are joined for the page's rows only. Text search on descriptions is
a plain `ILIKE` over that ordered walk: fine for one person's history, not
a full-text index. Wildcards a person types (`%`, `_`) are escaped and
searched for literally.

### Notifications and activity

Notifications are written by the Kafka consumer from committed events,
with their final wording, and deduplicated by event id like every other
consumer here. Transfers arrive on `ledger.events`; deposits and
withdrawals on that topic are skipped before the dedup insert, so the
consumer does not write a row per event for traffic it has nothing to say
about.

The activity screen reads the same append-only `audit_logs` rows the
platform keeps for itself, limited to rows the caller acted in. Before and
after state stays internal.

### Monthly partitions are kept ahead of the calendar

Not a feature anyone sees, but found while building these: the first
migration creates partitions for the current month and the next three, and
its comment says the application creates the rest on a schedule. Nothing
did. A database older than four months would have refused every money
movement. `PartitionMaintenance` now checks at startup and once a day, and
creates what is missing under an advisory lock.

## Running the migration

`V6__product_features.sql` only adds: new tables, nullable columns and
indexes. Code from before the migration keeps working against the new
schema. It applied in about 0.3 seconds to the 5 million transaction
development database.

One thing to know when deploying to a system that already has traffic: the
notification consumer starts reading `ledger.events` from the earliest
retained offset, so people may receive "money received" notifications for
transfers from the retention window.

## The dashboard

React and TypeScript, no UI framework and no chart library. Routes live in
the URL hash, so every screen and every filtered list can be bookmarked
and the server needs no route of its own.

Design rules, all in `web/src/styles.css`:

- One light theme. Neutral greys carry the layout. One brand colour, a
  deep green, is kept for primary actions and money arriving.
- Money in is green with a plus sign. Money out is plain ink with a minus
  sign. Direction is never carried by colour alone.
- Status is a labelled pill. The words carry the meaning.
- The two chart colours were checked as a pair for colour-blind readers
  (CVD separation 16.3, normal vision 18.2, both above 3:1 on white). The
  cash flow chart also has a table view with the same numbers.
- Fonts (IBM Plex Sans and Mono, Latin subset) are bundled with the app.
  The page makes no request to a font service.
- Works at phone width: the sidebar becomes a drawer and tables shorten
  their text instead of scrolling sideways.

Every request that moves money carries an idempotency key that is created
once per attempt and reused if the person retries. The receipt after a
transfer has a "Send the same request again" button that shows the server
returning the first result instead of moving money twice.

## Tests

| What | Where |
|---|---|
| Recipients, name check, freeze, rename | `RecipientsAndFreezeIT` |
| Search, entries, statement, CSV, insights | `SearchAndInsightsIT`, `CsvCellTest` |
| Dispute flow, payments list | `DisputeIT` |
| Schedules, refusals, concurrency | `ScheduledTransferIT`, `ScheduleDatesTest` |
| Money requests, concurrent pay | `PaymentRequestIT` |
| Inbox, profile, password, activity | `InboxAndProfileIT` |
| Partition maintenance | `PartitionMaintenanceIT` |

The dashboard was also driven through 23 flows in a headless browser
against the packaged jar (register, add money, send with name check,
schedule, pay, request, dispute, export, settings). That script is not
part of the repository's automated tests yet.

## Not built, and why

| Feature | Reason |
|---|---|
| Team roles and payment approvals | The most common feature in the survey, but it needs organisations and shared accounts, which this single-owner model does not have. |
| API keys and signed webhooks | A product of its own: key scopes, signing, retries, delivery logs. |
| Two-factor sign-in, session list | Tokens are stateless by design here; doing this properly means server-side sessions first. |
| More than one currency | Needs exchange rates and conversion entries in the ledger. The schema is ready for it; the product is not. |
| Spending limits | Would add a query to every money movement. Worth doing with a measured design, not as an aside. |
| PDF statements, savings goals | Smaller, left out to keep the scope honest. |
| Dark theme | One theme done carefully was preferred to two done quickly. Colours are tokens, so it can be added. |
