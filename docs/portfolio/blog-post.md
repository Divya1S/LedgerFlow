# Chaos testing my payments ledger found two bugs my 40 integration tests missed

Draft for dev.to / Medium / LinkedIn. Publish under your own name; edit
the voice freely. Suggested tags: postgresql, distributed-systems, java,
testing.

---

I spent weeks building LedgerFlow, a double-entry payments platform on
PostgreSQL, the way I imagined a staff engineer would review it: balance
invariants enforced by database triggers, a thousand-concurrent-transfer
test proving no double spends, idempotency keys that survive restarts,
a transactional outbox to Kafka. Forty-plus integration tests, all green,
all running against real Postgres, Kafka and Redis containers.

Then I wrote a chaos script that killed my dependencies while money was
moving, and learned two things my test suite could not teach me.

## The setup

The script is simple: 120 seconds of continuous transfers, and while they
run, stop Kafka at t=20s, restart it at t=45s, pause Redis at t=60s,
unpause at t=85s. Afterwards, audit the books in SQL: every transaction's
ledger entries must sum to zero, every balance must equal the sum of its
entries, the outbox must drain.

The books survived. The latency did not.

## Bug 1: fail-open is only as fast as the timeout in front of it

My Redis layer was designed to fail open. Cache miss? Go to Postgres.
Redis down? A breaker trips and everything skips Redis for 10 seconds.
Correctness never depended on Redis, and I had an integration test
pausing the Redis container to prove requests kept succeeding.

The chaos run showed requests hanging for up to 25.1 seconds.

The breaker trips after the first failure. But the first failure has to
happen, and Lettuce's default command timeout is 60 seconds. When Redis
is paused (not dead: the TCP connection stays open, packets just vanish),
the first unlucky requests sit inside that timeout. My test had missed it
because a stopped container refuses connections instantly; a paused one
does not. Production failure modes are more like the pause.

The fix was one line of configuration: 500ms command and connect
timeouts. Post-fix chaos run: max latency 533ms, down from 25 seconds.

## Bug 2: throughput claims need to be load-tested, not eyeballed

The transactional outbox polled 100 rows every 500ms: 200 events per
second, which sounded like plenty. The chaos load pushed about 1,100
transfers per second, each writing an outbox event. By the end of the
two-minute run the outbox was 65,012 events behind.

Nothing was lost (that is the whole point of the pattern: the events sit
in Postgres until published), but a fraud check delayed by five minutes
is a fraud check that failed at its job. I resized the publisher from
measurement: 1,000 rows per 200ms poll. The next run drained to zero
within the 30-second grace period.

## What I took away

1. Integration tests verify logic. Chaos tests verify behavior: the
   difference is where the 25 seconds hid.
2. "Stopped" and "paused" are different failure modes. Test the one that
   looks like a network partition, not just the one that fails fast.
3. Every capacity number in a config file is a claim. Load prove it or
   assume it is wrong.
4. Put invariants in the database. Through every outage I threw at it,
   the deferred trigger that rejects unbalanced ledger commits meant the
   worst case was slow, never wrong. Slow is recoverable.

The repo, with the chaos script, both measured runs and everything else:
github.com/Divya1S/LedgerFlow
