# ADR-010: Hold the first larger payment to someone new

Status: accepted (November 2026)

## Context

The feature list in [product-features.md](../product-features.md) came from
what comparable products ship. This decision came from the other
direction: what their customers complain about most.

**The complaint.** A payment sent to a scammer, or to the wrong person,
cannot be taken back.

| Source | What it shows |
|---|---|
| [CFPB Consumer Response annual report, 2024 data](https://files.consumerfinance.gov/f/documents/cfpb_cr-annual-report_2025-05.pdf) | "Fraud or scam" is the top issue for money transfer and payment app complaints: 37% of 27,400. For domestic money transfers alone it is 47%. |
| [FTC Consumer Sentinel data book 2024](https://www.ftc.gov/system/files/ftc_gov/pdf/csn-annual-data-book-2024.pdf) | 90,571 fraud reports paid by payment app or service, $391 million lost. |
| [Pew Research, 2022](https://www.pewresearch.org/short-reads/2022/09/08/payment-apps-like-venmo-and-cash-app-bring-convenience-and-security-concerns-to-some-users/) | 13% of payment app users have sent money to someone and later realised it was a scam. |
| [UK Finance, 2025 data](https://www.ukfinance.org.uk/news-and-insight/press-release/fraud-report-2026-press-release) | 248,070 authorised push payment fraud cases, £576.4 million. |
| [US Senate PSI report on Zelle, 2024](https://www.hsgac.senate.gov/wp-content/uploads/2024.7.23-PSI-Majority-Staff-Report-on-Zelle.pdf) | Three large banks reimbursed 12% of scam disputes. |
| [Venmo help centre](https://help.venmo.com/hc/en-us/articles/235171088-Cancel-Payment) | "There isn't a way to cancel a payment once it's sent." |

**The second complaint, which constrains the answer.** On review sites
the dominant theme for PayPal and Stripe is the opposite problem: money
frozen or held with no explanation and nobody to ask. In the CFPB data,
"money was not available when promised" is 9% and trouble reaching wallet
funds another 8%. A fix for the first complaint that creates the second
is not a fix.

**What LedgerFlow did before.** A transfer was final the instant it was
sent. The name check before paying (confirmation of payee) helps with
typos. It does nothing for a person who is being talked into paying the
"right" account by a scammer.

## Decision

A wallet-to-wallet transfer is held, not delivered, when all of these
are true:

1. it is for $200 or more (configurable), and
2. the sender and the recipient have never settled a payment with each
   other, in either direction.

The money leaves the sender at once and waits in the platform suspense
account. It is delivered automatically after one hour. If there is
evidence about the recipient (their account is under 7 days old, or two
or more other strangers have had first payments to them held in the last
day) it waits 24 hours instead. Until delivery, the sender can cancel
and gets all of it back.

Everything else is instant, as before.

## Why this shape

- **It targets the costly case, not every payment.** The Reserve Bank of
  India found that transfers above Rs 10,000 are 45% of fraud cases but
  98.5% of fraud value. Zelle's mean payment is about $278. A threshold
  of $200 leaves everyday payments alone and covers most of the money at
  risk. Payments below it run no extra query at all.
- **It follows a regulator's design rather than inventing one.** In April
  2026 the RBI proposed almost exactly this: a one hour lag on larger
  first transfers, cancellable by the payer, exempting known payees,
  merchants and recurring payments. Singapore's banks hold or reject
  suspicious large transfers for 24 hours. The UK allows payments to be
  delayed up to four business days on suspicion of fraud.
- **Trust comes from a payment that arrived.** Not from saving a
  recipient, which a scammer can talk someone into. A cancelled hold
  does not count. After one delivered payment the pair is never held
  again, in either direction, so the cost is paid once per relationship.
- **The sender cannot skip the hold.** Scammers coach people through
  warnings. The value of a cooling-off period is that it outlasts the
  phone call. This is the main cost of the design and it is deliberate.
- **The hold is the opposite of an opaque freeze.** The second complaint
  is designed against directly:
  - The sender is told before sending that it will be held, for how
    long, and why (`GET /transfers/protection-check`).
  - Release needs no approval. A runner delivers at `release_at`.
  - The reasons are stored and shown in plain words.
  - Both people are notified at each step.
  - The recipient sees that money is coming and when, never the reasons.

## How the books record it

Three new transaction types, each a balanced posting:

```
HOLD          sender -X, suspense +X      when sent
HOLD_RELEASE  suspense -X, recipient +X   when the hold ends
HOLD_RETURN   suspense -X, sender +X      when cancelled or undeliverable
```

Held money is never "pending" in a flag somewhere. It is in an account,
which gives an invariant anyone can check:

```sql
SELECT (SELECT balance FROM account_balances WHERE account_id = :suspense)
     - (SELECT COALESCE(sum(amount), 0) FROM transfer_holds WHERE status = 'HELD');
-- always 0
```

A hold has exactly two ways out and a row lock makes them exclusive.
`TransferHoldIT` runs four cancels against four release polls on one due
hold and asserts exactly one settling transaction exists afterwards.
Release takes the lock with `SKIP LOCKED`, so several instances can poll.
The lock, the credit and the status change commit together, so a crash
cannot deliver money and leave the hold looking open.

Each person's history shows only the transaction that touched their own
balance: the sender sees the hold, the recipient sees the release, and
neither sees a transfer twice. Money that left and came back is kept out
of "money in" and "money out" on the overview, because it was neither.

The check sits inside `TransferService.transfer`, the one path for
wallet-to-wallet money. Scheduled transfers and money requests go through
it without knowing. Paying a stranger's request link is a common scam, so
it is held too, and cancelling the hold reopens the request.

## Alternatives considered

| Option | Why not |
|---|---|
| Warnings only, no hold | What most apps do. Cash App warns on about 1.5% of payments. A warning is read in the same hurry that causes the loss. |
| Let the sender release early | Removes the protection exactly when it is needed: when someone is on the phone telling them to. |
| Hold every transfer for a few seconds ("undo send") | Catches a typo, not a scam, and delays every payment to do it. |
| Reserve the money in the sender's wallet instead of moving it | Needs a second balance (available vs actual) in the hot path and in the floor constraint. Moving it to suspense reuses the existing movement and keeps one balance. |
| Reimburse victims afterwards | The UK does this by rule and scam value fell about 21%. It needs a claims process, a liability split and a fraud team. A portfolio project cannot honestly show that. |
| Platform review before release | A person in the loop is how a hold becomes the second complaint. |

## Consequences

- **A real first payment to someone new is delayed.** A deposit to a new
  landlord on the due date arrives an hour later. The arrival time is
  shown before sending. This is the price, stated plainly.
- **About half of scam cases are below any sensible threshold** and are
  not held. The design protects value, not every victim. Lowering the
  threshold trades complaints of the first kind for the second.
- **Repeat victims are not protected.** A scam that takes several
  payments gets through after the first one arrives.
- **One more lookup on larger transfers.** The "have these two settled
  before" query took 0.2 ms for a known person and 1.1 ms in the worst
  case (a stranger, all partitions) on the 5 million transaction
  development database. If it ever became hot, a small table of known
  pairs written on release would replace it.
- **The suspense account is a hot row** during hold activity, like the
  cash account is for deposits. It is locked in the same sorted order as
  every other balance.
- **Not measured.** Whether holds reduce losses here cannot be shown
  without real users. In production the numbers to watch would be: share
  of holds cancelled (a cancel is a loss avoided or a mistake caught),
  scam reports per thousand first payments, and support contacts about
  "where is my money".
- **One rule had to move into the schema.** The first full test run
  failed: an older test inserted a second USD suspense account, the
  lookup by type and currency found two, and placing a hold broke. The
  code had assumed one per currency; nothing enforced it. A partial
  unique index (V9) now does, so held money can never be split across
  two suspense accounts.
- The threshold and both durations are configuration. The migration that
  widened the transaction type check took 50 ms under its exclusive lock
  on the development database; validation ran separately in 979 ms
  without blocking writes.
