# Concurrency

How LedgerFlow keeps money correct when many requests hit the same accounts
at once, and the tests that prove it.

## The problem

Account balance is 100. Two requests each try to transfer 80 at the same
time. Without protection both read "100, enough", both write, and the
account ends at minus 60. That is a double spend.

## The strategy

Three layers, from primary defense to last resort:

1. **Row locks in a fixed order.** Every money movement goes through one
   code path, [MoneyMovementService](../src/main/java/com/ledgerflow/ledger/domain/MoneyMovementService.java).
   It locks the `account_balances` rows it will touch with
   `SELECT ... FOR UPDATE`, one query per row, in ascending account id
   order. The second transfer waits until the first commits, then reads the
   updated balance and fails with INSUFFICIENT_FUNDS if the money is gone.
2. **Retry on transient errors.** PostgreSQL resolves deadlocks by killing
   one transaction (SQLSTATE 40P01). [TransientDbRetry](../src/main/java/com/ledgerflow/common/db/TransientDbRetry.java)
   reruns the whole transaction up to 3 times with backoff. It sits outside
   the @Transactional boundary because the failed transaction is dead; only
   a new one can succeed.
3. **Database constraints as backstop.** Even if the application locking
   were broken, `CHECK (balance >= min_balance)` on `account_balances`
   rejects a negative wallet, and the deferred trigger rejects unbalanced
   ledger postings at commit. Bugs turn into errors, not into lost money.

## Why ordered locking instead of alternatives

| Option | Verdict |
|---|---|
| `SELECT FOR UPDATE`, sorted order | Chosen. Simple, no retry storm under contention, deadlock free by construction between movements. |
| Optimistic locking (version column) | The version column exists, but under a hot account almost every attempt would conflict and retry. Pessimistic locks queue instead of thrash. |
| SERIALIZABLE isolation | Correct but turns contention into 40001 retry loops and costs more on every transaction. Explicit locks target exactly the rows that need protection. |
| Advisory locks | Another lock namespace to reason about, with no benefit over row locks here. |

## Isolation level

READ COMMITTED (PostgreSQL default). It is enough because every decision
that depends on a balance happens after taking the row lock, which always
sees the latest committed value. We do not rely on repeatable reads
anywhere in the money path. This choice is deliberate and documented in
ADR-005 (Phase 10).

## Deadlocks

Two transactions locking A then B and B then A wait on each other forever;
PostgreSQL notices after deadlock_timeout (1s) and kills one with 40P01.

- [DeadlockIT](../src/test/java/com/ledgerflow/ledger/DeadlockIT.java)
  reproduces exactly that with opposite lock orders and asserts one
  transaction wins, one is killed, and the retry wrapper turns the loser
  into an eventual success.
- Movements cannot do this to each other because they all lock in ascending
  account id order. The bidirectional storm test (200 A to B and 200 B to A
  at once) asserts zero deadlocks.

## Idempotency under concurrency

The claim INSERT into `idempotency_keys`, the money movement, and the
response snapshot all commit in one transaction
([IdempotencyService](../src/main/java/com/ledgerflow/common/idempotency/IdempotencyService.java)).
A concurrent duplicate blocks on the unique index until the first finishes,
then replays the stored response. A crash rolls back the claim with
everything else, so a retry re-executes cleanly. Restart safety comes from
the state living in PostgreSQL, not in memory.

## The proof

[ConcurrentTransferIT](../src/test/java/com/ledgerflow/transfer/ConcurrentTransferIT.java),
run against real PostgreSQL 17 in CI:

- **1000 simultaneous transfers** against one funded account, where only
  half can succeed. Result: exactly 500 succeed, exactly 500 fail with
  INSUFFICIENT_FUNDS, final balances are exact, ledger sums to zero, every
  account balance equals the sum of its ledger entries.
- **Double spend race**: two concurrent 80 transfers from a 100 balance.
  Exactly one succeeds.
- **Opposing storms**: 400 transfers in both directions between two
  accounts at once. Zero deadlocks, books exact.

[IdempotencyIT](../src/test/java/com/ledgerflow/transfer/IdempotencyIT.java)
fires concurrent duplicates over HTTP and asserts exactly one transaction
row exists afterwards.

## What happens when it still fails

- Pool exhausted or database down: the request fails with an error, never
  with a fake success. The Hikari pool fails fast (3s) instead of queueing
  forever.
- Transaction killed mid-flight: everything rolls back together (movement,
  ledger, audit, outbox, idempotency claim). There is no partial state to
  clean up, which is the point of doing it all in one transaction.
