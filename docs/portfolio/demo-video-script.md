# Demo Video Script (2 minutes)

Record with any screen recorder (QuickTime, Loom, OBS). Target: 2:00 to
2:30. One take is fine; recruiters value clarity over polish. Before
recording: `docker compose up -d`, then
`AI_ENABLED=true GEMINI_API_KEY=$(cat ~/.gemini_api_key) java -jar target/ledgerflow-0.1.0-SNAPSHOT.jar`
(build first with `mvn -Pwith-ui -DskipTests package`).

## 0:00 - 0:15 | Hook

Screen: the dashboard at localhost:8080, already signed in, accounts view.

Say: "This is LedgerFlow, a payments platform I built where PostgreSQL is
the source of truth: double-entry ledger, ACID transfers, and every
number you'll see in the repo was actually measured."

## 0:15 - 0:40 | Money movement + idempotency

Screen: Move Money tab. Do a deposit, then click Submit AGAIN with the
same idempotency key; point at the yellow replay notice.

Say: "Every money endpoint is idempotent. Same key twice: the server
recognizes the retry and replays the stored response instead of moving
money twice. The key, the transfer and the response snapshot commit in
one database transaction, so this survives crashes and restarts."

## 0:40 - 1:10 | Payment, fraud rules, AI analyst

Screen: Payments tab. Pay your merchant account $1,500. The fraud verdict
chip flips to REVIEW; the AI analyst card appears a few seconds later.

Say: "Payments flow through a transactional outbox to Kafka. A fraud
service consumes them: deterministic rules first, and for flagged
payments a Gemini-powered analyst investigates through read-only tools
and writes a risk assessment. It can flag money but it physically cannot
touch it, and its accuracy is measured by an eval suite in the repo."

## 1:10 - 1:40 | The proof

Screen: terminal. Run:

```bash
docker exec ledgerflow-postgres psql -U ledgerflow -d ledgerflow -c \
  "SELECT coalesce(sum(amount),0) AS global_ledger_sum FROM ledger_entries;"
```

Say: "The invariant: every ledger entry across the whole system sums to
exactly zero, enforced by a database trigger at commit. I proved it with
a thousand concurrent transfers against one account, a reproduced
deadlock, and a chaos test that killed Kafka and Redis under load:
102,000 requests, zero failures, books exact."

## 1:40 - 2:00 | Close

Screen: the GitHub README (badges, benchmark table).

Say: "Everything is in the repo: 45 tests on real Postgres, Kafka and
Redis containers, measured EXPLAIN ANALYZE optimizations like 403
milliseconds to half a millisecond on statement queries, Helm deployment,
and nine architecture decision records. Thanks for watching."

## Tips

- Hide bookmarks bar and notifications; 1280x800 window records well.
- If you flub a line, keep going; only re-record on technical failure.
- Upload unlisted to YouTube, link it at the top of the README.
