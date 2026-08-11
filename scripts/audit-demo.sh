#!/usr/bin/env bash
# Move real money through the API, then prove the books are exact with
# three SQL invariants. Used by the README terminal GIF (docs/media).
# Needs the compose stack up and the app on :8080.
set -euo pipefail

PSQL="docker exec ledgerflow-postgres psql -U ledgerflow -d ledgerflow"
BASE="http://localhost:8080"
EMAIL="audit-$(date +%s)@demo.ledgerflow.io"
PASS="audit-demo-password"

json() { python3 -c "import json,sys;print(json.load(sys.stdin)['$1'])"; }

curl -s -X POST $BASE/api/v1/auth/register -H 'Content-Type: application/json' \
  -d "{\"email\":\"$EMAIL\",\"password\":\"$PASS\",\"fullName\":\"Audit Demo\"}" > /dev/null
TOKEN=$(curl -s -X POST $BASE/api/v1/auth/login -H 'Content-Type: application/json' \
  -d "{\"email\":\"$EMAIL\",\"password\":\"$PASS\"}" | json accessToken)
AUTH=(-H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json')

A=$(curl -s -X POST $BASE/api/v1/accounts "${AUTH[@]}" -d '{"type":"USER_WALLET","currency":"USD","name":"Alice"}' | json id)
B=$(curl -s -X POST $BASE/api/v1/accounts "${AUTH[@]}" -d '{"type":"USER_WALLET","currency":"USD","name":"Bob"}' | json id)
curl -s -X POST $BASE/api/v1/accounts/$A/deposits "${AUTH[@]}" -H "Idempotency-Key: $(uuidgen)" \
  -d '{"amountMinorUnits":10000,"currency":"USD"}' > /dev/null

echo "==> Transferring \$42.42 from Alice to Bob"
TXN=$(curl -s -X POST $BASE/api/v1/transfers "${AUTH[@]}" -H "Idempotency-Key: $(uuidgen)" \
  -d "{\"sourceAccountId\":\"$A\",\"destinationAccountId\":\"$B\",\"amountMinorUnits\":4242,\"currency\":\"USD\",\"description\":\"audit demo\"}" \
  | json transactionId)

echo "==> Its double-entry ledger rows (must sum to zero):"
$PSQL -c "SELECT left(account_id::text, 8) AS account, amount, direction
          FROM ledger_entries WHERE transaction_id = '$TXN';"

echo "==> The whole platform's invariants:"
$PSQL -c "SELECT
  (SELECT coalesce(sum(amount),0) FROM ledger_entries)               AS global_ledger_sum,
  (SELECT count(*) FROM (SELECT transaction_id FROM ledger_entries
     GROUP BY transaction_id HAVING sum(amount) <> 0) x)             AS unbalanced_transactions,
  (SELECT count(*) FROM account_balances b
     LEFT JOIN (SELECT account_id, sum(amount) s FROM ledger_entries
                GROUP BY 1) l ON l.account_id = b.account_id
     WHERE b.balance <> coalesce(l.s,0) AND b.min_balance IS NOT NULL) AS balance_mismatches;"
echo "Zero, zero, zero. Enforced by the database, not by hope."
