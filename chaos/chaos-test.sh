#!/usr/bin/env bash
# LedgerFlow chaos test: kill Kafka and Redis while money is moving, then
# prove the books are still exact.
#
# Prerequisites: docker compose stack up, app running on :8080 with
# RATE_LIMIT_MONEY_PER_MINUTE raised (see load/README.md).
#
# Usage: ./chaos/chaos-test.sh

set -euo pipefail
cd "$(dirname "$0")/.."

PSQL="docker exec ledgerflow-postgres psql -U ledgerflow -d ledgerflow -t -A"

echo "== chaos: starting background transfer load (120s) =="
k6 run --quiet --summary-export=chaos/last-run-summary.json \
   -e BASE_URL="${BASE_URL:-http://localhost:8080}" \
   chaos/chaos-load.js &
K6_PID=$!

sleep 20
echo "== chaos: STOPPING KAFKA (t=20s) =="
docker stop ledgerflow-kafka > /dev/null

sleep 25
echo "== chaos: restarting kafka (t=45s) =="
docker start ledgerflow-kafka > /dev/null

sleep 15
echo "== chaos: PAUSING REDIS (t=60s) =="
docker pause ledgerflow-redis > /dev/null

sleep 25
echo "== chaos: unpausing redis (t=85s) =="
docker unpause ledgerflow-redis > /dev/null

wait $K6_PID
echo "== chaos: load finished, letting outbox drain 30s =="
sleep 30

echo "== chaos: ledger invariant checks =="
GLOBAL_SUM=$($PSQL -c "SELECT COALESCE(sum(amount),0) FROM ledger_entries")
UNBALANCED=$($PSQL -c "SELECT count(*) FROM (SELECT transaction_id FROM ledger_entries GROUP BY transaction_id HAVING sum(amount) <> 0) x")
MISMATCHED=$($PSQL -c "SELECT count(*) FROM account_balances b LEFT JOIN (SELECT account_id, sum(amount) s FROM ledger_entries GROUP BY 1) l ON l.account_id = b.account_id WHERE b.balance <> COALESCE(l.s, 0) AND b.min_balance IS NOT NULL")
PENDING_OUTBOX=$($PSQL -c "SELECT count(*) FROM outbox_events WHERE status = 'PENDING'")
FAILED_OUTBOX=$($PSQL -c "SELECT count(*) FROM outbox_events WHERE status = 'FAILED'")

echo "global ledger sum:            $GLOBAL_SUM (must be 0)"
echo "unbalanced transactions:      $UNBALANCED (must be 0)"
echo "balance/ledger mismatches:    $MISMATCHED (must be 0; floor-constrained accounts)"
echo "outbox still pending:         $PENDING_OUTBOX (should drain to 0)"
echo "outbox parked FAILED:         $FAILED_OUTBOX"

if [ "$GLOBAL_SUM" != "0" ] || [ "$UNBALANCED" != "0" ] || [ "$MISMATCHED" != "0" ]; then
    echo "CHAOS TEST FAILED: financial invariants violated"
    exit 1
fi
echo "== chaos: PASSED, money stayed correct through Kafka and Redis outages =="
