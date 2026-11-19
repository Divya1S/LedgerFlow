#!/usr/bin/env bash
# Fills a local LedgerFlow with two demo profiles and about eleven weeks
# of believable history, so the dashboard has something to show.
#
#   ./scripts/seed-demo.sh
#
# Needs the compose stack up and the app on :8080. Safe to run again: it
# stops early when the demo profiles already exist.
#
# How it works:
#   1. Profiles and accounts are created through the real API.
#   2. Past transactions are inserted with SQL, because the API can only
#      create transactions dated "now". Every inserted transaction has
#      balanced ledger entries, so the database's own zero-sum trigger
#      checks each one when the script commits, and balances are updated
#      from those same entries.
#   3. A few things are then done live through the API (a flagged payment,
#      a dispute, a scheduled transfer, a money request) so notifications,
#      the fraud check and the activity log have real content.
set -euo pipefail

BASE="${BASE:-http://localhost:8080}"
PSQL=(docker exec -i ledgerflow-postgres psql -U ledgerflow -d ledgerflow -v ON_ERROR_STOP=1 -q)
PASS="demo-password-123"
# Set DEMO_DOMAIN to create a second, separate set of demo profiles.
DOMAIN="${DEMO_DOMAIN:-demo.ledgerflow.io}"
MAYA="maya@$DOMAIN"
DANIEL="daniel@$DOMAIN"
SHOPS="shops@$DOMAIN"
SAM="sam@$DOMAIN"
LENA="lena@$DOMAIN"

field() { python3 -c "import json,sys;print(json.load(sys.stdin)['$1'])"; }

api() { # api METHOD PATH TOKEN [JSON]
  local method="$1" path="$2" token="$3" body="${4:-}"
  local args=(-s --fail-with-body -X "$method" "$BASE$path" -H 'Content-Type: application/json'
              -H "Idempotency-Key: $(uuidgen)")
  [ -n "$token" ] && args+=(-H "Authorization: Bearer $token")
  [ -n "$body" ] && args+=(-d "$body")
  curl "${args[@]}"
}

login() { api POST /api/v1/auth/login "" "{\"email\":\"$1\",\"password\":\"$PASS\"}" | field accessToken; }

register() { # register EMAIL NAME -> token
  api POST /api/v1/auth/register "" "{\"email\":\"$1\",\"password\":\"$PASS\",\"fullName\":\"$2\"}" > /dev/null
  login "$1"
}

open_account() { # open_account TOKEN TYPE NAME [CATEGORY] -> id
  local category=""
  [ -n "${4:-}" ] && category=",\"category\":\"$4\""
  api POST /api/v1/accounts "$1" "{\"type\":\"$2\",\"currency\":\"USD\",\"name\":\"$3\"$category}" | field id
}

if ! curl -s -o /dev/null "$BASE/actuator/health"; then
  echo "LedgerFlow is not answering on $BASE. Start it first (see README)." >&2
  exit 1
fi

if curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/api/v1/auth/login" -H 'Content-Type: application/json' \
     -d "{\"email\":\"$MAYA\",\"password\":\"$PASS\"}" | grep -q 200; then
  echo "Demo data is already there. Sign in as $MAYA with password $PASS"
  exit 0
fi

echo "==> Creating demo profiles and accounts"
MAYA_T=$(register "$MAYA" "Maya Chen")
DANIEL_T=$(register "$DANIEL" "Daniel Okafor")
SHOPS_T=$(register "$SHOPS" "Priya Nair")

EVERYDAY=$(open_account "$MAYA_T" USER_WALLET "Everyday")
SAVINGS=$(open_account "$MAYA_T" USER_WALLET "Savings")
TRAVEL=$(open_account "$MAYA_T" USER_WALLET "Travel fund")
STUDIO=$(open_account "$MAYA_T" MERCHANT "Chen Design Studio" SERVICES)
DANIEL_W=$(open_account "$DANIEL_T" USER_WALLET "Main")
MARKET=$(open_account "$SHOPS_T" MERCHANT "Harbor Market" GROCERIES)
CAFE=$(open_account "$SHOPS_T" MERCHANT "Juniper Cafe" DINING)
TRANSIT=$(open_account "$SHOPS_T" MERCHANT "Northline Transit" TRANSPORT)
ENERGY=$(open_account "$SHOPS_T" MERCHANT "Brightside Energy" UTILITIES)
BOOKS=$(open_account "$SHOPS_T" MERCHANT "Fable Books" SHOPPING)
CINEMA=$(open_account "$SHOPS_T" MERCHANT "Lumen Cinema" ENTERTAINMENT)

echo "==> Writing eleven weeks of history (checked by the ledger's zero-sum trigger)"
"${PSQL[@]}" \
  -v everyday="$EVERYDAY" -v savings="$SAVINGS" -v travel="$TRAVEL" -v studio="$STUDIO" \
  -v daniel="$DANIEL_W" -v market="$MARKET" -v cafe="$CAFE" -v transit="$TRANSIT" \
  -v energy="$ENERGY" -v books="$BOOKS" -v cinema="$CINEMA" <<'SQL'
BEGIN;

\set cash '00000000-0000-7000-8000-000000000001'
\set fees '00000000-0000-7000-8000-000000000002'

CREATE TEMP TABLE plan (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    ts          TIMESTAMPTZ NOT NULL,
    type        TEXT NOT NULL,
    src         UUID NOT NULL,
    dst         UUID NOT NULL,
    amount      BIGINT NOT NULL,
    description TEXT
) ON COMMIT DROP;

SELECT setseed(0.42) \gset

-- One row per past day, 77 days back to yesterday, at 00:00 UTC.
CREATE TEMP TABLE days ON COMMIT DROP AS
SELECT n, (date_trunc('day', now() AT TIME ZONE 'UTC') - make_interval(days => n)) AT TIME ZONE 'UTC' AS day
FROM generate_series(1, 77) AS n;

INSERT INTO plan (ts, type, src, dst, amount, description)
-- money to start with
SELECT day + interval '15 hours', 'DEPOSIT', :'cash'::uuid, :'everyday'::uuid, 185000, 'Opening transfer' FROM days WHERE n = 77
UNION ALL SELECT day + interval '15 hours', 'DEPOSIT', :'cash'::uuid, :'daniel'::uuid, 600000, 'Opening transfer' FROM days WHERE n = 77
-- salary every two weeks
UNION ALL SELECT day + interval '16 hours', 'DEPOSIT', :'cash'::uuid, :'everyday'::uuid, 240000, 'Salary' FROM days WHERE n % 14 = 3
-- rent on the first of the month
UNION ALL SELECT day + interval '17 hours', 'TRANSFER', :'everyday'::uuid, :'daniel'::uuid, 115000, 'Rent' FROM days WHERE extract(day FROM day) = 1
-- savings every week, travel fund now and then (her own accounts)
UNION ALL SELECT day + interval '18 hours', 'TRANSFER', :'everyday'::uuid, :'savings'::uuid, 15000, 'Weekly savings' FROM days WHERE n % 7 = 2
UNION ALL SELECT day + interval '18 hours', 'TRANSFER', :'everyday'::uuid, :'travel'::uuid, 20000, 'Trip to Lisbon' FROM days WHERE n IN (40, 12)
-- groceries roughly twice a week
UNION ALL SELECT day + interval '19 hours' + random() * interval '2 hours', 'PAYMENT', :'everyday'::uuid, :'market'::uuid,
                 (3500 + floor(random() * 8500))::bigint, 'Groceries' FROM days WHERE n % 4 = 0 OR n % 9 = 5
-- coffee and lunch
UNION ALL SELECT day + interval '16 hours' + random() * interval '3 hours', 'PAYMENT', :'everyday'::uuid, :'cafe'::uuid,
                 (450 + floor(random() * 1400))::bigint,
                 (ARRAY['Flat white', 'Lunch', 'Coffee and pastry', 'Breakfast'])[1 + floor(random() * 4)::int]
          FROM days WHERE n % 2 = 0 AND n % 10 <> 0
-- bus fares on working days
UNION ALL SELECT day + interval '15 hours' + random() * interval '1 hour', 'PAYMENT', :'everyday'::uuid, :'transit'::uuid,
                 275, 'Bus fare' FROM days WHERE n % 7 IN (1, 3, 4)
-- electricity once a month
UNION ALL SELECT day + interval '20 hours', 'PAYMENT', :'everyday'::uuid, :'energy'::uuid, 8640, 'Electricity' FROM days WHERE extract(day FROM day) = 12
-- books and films
UNION ALL SELECT day + interval '21 hours', 'PAYMENT', :'everyday'::uuid, :'books'::uuid,
                 (1800 + floor(random() * 2400))::bigint, 'Books' FROM days WHERE n IN (66, 37, 15)
UNION ALL SELECT day + interval '22 hours', 'PAYMENT', :'everyday'::uuid, :'cinema'::uuid,
                 (1400 + floor(random() * 1800))::bigint, 'Two tickets' FROM days WHERE n IN (58, 44, 23, 6)
-- cash out
UNION ALL SELECT day + interval '19 hours', 'WITHDRAWAL', :'everyday'::uuid, :'cash'::uuid, 20000, 'Cash withdrawal' FROM days WHERE n IN (51, 20)
-- a friend paying her back
UNION ALL SELECT day + interval '21 hours', 'TRANSFER', :'daniel'::uuid, :'everyday'::uuid, 4200, 'Concert' FROM days WHERE n = 61
UNION ALL SELECT day + interval '21 hours', 'TRANSFER', :'daniel'::uuid, :'everyday'::uuid, 2500, 'Groceries split' FROM days WHERE n = 33
UNION ALL SELECT day + interval '21 hours', 'TRANSFER', :'daniel'::uuid, :'everyday'::uuid, 6000, 'Weekend trip' FROM days WHERE n = 12
-- clients paying her design studio
UNION ALL SELECT day + interval '17 hours', 'PAYMENT', :'daniel'::uuid, :'studio'::uuid, 45000, 'Website mockups' FROM days WHERE n = 55
UNION ALL SELECT day + interval '17 hours', 'PAYMENT', :'daniel'::uuid, :'studio'::uuid, 62000, 'Brand guide' FROM days WHERE n = 27
UNION ALL SELECT day + interval '17 hours', 'PAYMENT', :'daniel'::uuid, :'studio'::uuid, 18000, 'Logo revision' FROM days WHERE n = 9;

INSERT INTO transactions (id, created_at, type, status, amount, currency, source_account_id, destination_account_id, description)
SELECT id, ts, type, 'COMPLETED', amount, 'USD', src, dst, description FROM plan;

-- Both sides of every movement. Payments carry the 1% platform fee as a third entry.
INSERT INTO ledger_entries (id, transaction_id, created_at, account_id, amount, currency)
SELECT gen_random_uuid(), id, ts, src, -amount, 'USD' FROM plan
UNION ALL
SELECT gen_random_uuid(), id, ts, dst, amount - CASE WHEN type = 'PAYMENT' THEN amount / 100 ELSE 0 END, 'USD' FROM plan
UNION ALL
SELECT gen_random_uuid(), id, ts, :'fees'::uuid, amount / 100, 'USD' FROM plan WHERE type = 'PAYMENT' AND amount >= 100;

INSERT INTO payments (id, payer_user_id, source_account_id, destination_account_id, amount, currency, status,
                      transaction_id, transaction_created_at, created_at)
SELECT gen_random_uuid(), a.user_id, p.src, p.dst, p.amount, 'USD', 'COMPLETED', p.id, p.ts, p.ts
FROM plan p JOIN accounts a ON a.id = p.src
WHERE p.type = 'PAYMENT';

-- The fraud check passed these at the time.
INSERT INTO fraud_decisions (id, payment_id, verdict, score, rule_hits, evaluated_at)
SELECT gen_random_uuid(), pay.id, 'APPROVED', 0, '[]', pay.created_at + interval '1 second'
FROM payments pay JOIN plan p ON p.id = pay.transaction_id;

-- These people have eleven weeks of history, so their profiles and
-- accounts are that old too. (It also matters to the hold rule: an account
-- opened minutes ago is treated as a warning sign.)
UPDATE accounts SET created_at = now() - interval '78 days'
WHERE id IN (:'everyday', :'savings', :'travel', :'studio', :'daniel', :'market', :'cafe',
             :'transit', :'energy', :'books', :'cinema');
UPDATE users SET created_at = now() - interval '78 days'
WHERE id IN (SELECT user_id FROM accounts WHERE id IN (:'everyday', :'daniel', :'market'));

-- Balances come from the entries just written, never from a separate sum.
UPDATE account_balances b
SET balance = b.balance + d.delta, version = b.version + 1
FROM (SELECT e.account_id, sum(e.amount) AS delta
      FROM ledger_entries e JOIN plan p ON p.id = e.transaction_id AND p.ts = e.created_at
      GROUP BY e.account_id) d
WHERE b.account_id = d.account_id;

COMMIT;
SQL

echo "==> Doing a few things live, through the API"
# A large payment to Maya's studio: the fraud check flags it for review.
api POST /api/v1/payments "$DANIEL_T" "{\"sourceAccountId\":\"$DANIEL_W\",\"destinationAccountId\":\"$STUDIO\",\"amountMinorUnits\":125000,\"currency\":\"USD\",\"description\":\"Website build, first milestone\"}" > /dev/null

# Daniel disputes the logo revision he paid for nine days ago.
LOGO=$(api GET "/api/v1/payments?role=PAYER&limit=50" "$DANIEL_T" \
  | python3 -c "import json,sys;print(next(p['paymentId'] for p in json.load(sys.stdin)['items'] if p['description']=='Logo revision'))")
api POST "/api/v1/payments/$LOGO/disputes" "$DANIEL_T" '{"reason":"The files I received were the old version, not the revision we agreed on."}' > /dev/null

# Maya: saved recipients, two schedules, a money request, a payment, a frozen account.
api POST /api/v1/recipients "$MAYA_T" "{\"accountId\":\"$DANIEL_W\",\"nickname\":\"Daniel (rent)\"}" > /dev/null
api POST /api/v1/recipients "$MAYA_T" "{\"accountId\":\"$MARKET\",\"nickname\":\"Harbor Market\"}" > /dev/null
api POST /api/v1/recipients "$MAYA_T" "{\"accountId\":\"$CAFE\",\"nickname\":\"Juniper Cafe\"}" > /dev/null

# Dates are meant in this machine's time zone, the same as the dashboard sends.
ZONE=$(python3 -c "import os;p=os.path.realpath('/etc/localtime');print(p.split('zoneinfo/')[-1] if 'zoneinfo/' in p else 'UTC')")
NEXT_FIRST=$(python3 -c "import datetime as d;t=d.date.today();print((t.replace(day=1)+d.timedelta(days=32)).replace(day=1))")
IN_THREE=$(python3 -c "import datetime as d;print(d.date.today()+d.timedelta(days=3))")
api POST /api/v1/scheduled-transfers "$MAYA_T" "{\"sourceAccountId\":\"$EVERYDAY\",\"destinationAccountId\":\"$DANIEL_W\",\"amountMinorUnits\":115000,\"currency\":\"USD\",\"description\":\"Rent\",\"frequency\":\"MONTHLY\",\"startDate\":\"$NEXT_FIRST\",\"zone\":\"$ZONE\"}" > /dev/null
api POST /api/v1/scheduled-transfers "$MAYA_T" "{\"sourceAccountId\":\"$EVERYDAY\",\"destinationAccountId\":\"$SAVINGS\",\"amountMinorUnits\":15000,\"currency\":\"USD\",\"description\":\"Weekly savings\",\"frequency\":\"WEEKLY\",\"startDate\":\"$IN_THREE\",\"zone\":\"$ZONE\"}" > /dev/null

api POST /api/v1/payment-requests "$MAYA_T" "{\"destinationAccountId\":\"$EVERYDAY\",\"amountMinorUnits\":6400,\"currency\":\"USD\",\"note\":\"Concert tickets\"}" > /dev/null
api POST /api/v1/payments "$MAYA_T" "{\"sourceAccountId\":\"$EVERYDAY\",\"destinationAccountId\":\"$MARKET\",\"amountMinorUnits\":5420,\"currency\":\"USD\",\"description\":\"Groceries\"}" > /dev/null
api POST /api/v1/transfers "$DANIEL_T" "{\"sourceAccountId\":\"$DANIEL_W\",\"destinationAccountId\":\"$EVERYDAY\",\"amountMinorUnits\":4800,\"currency\":\"USD\",\"description\":\"Dinner on Friday\"}" > /dev/null
api POST "/api/v1/accounts/$TRAVEL/freeze" "$MAYA_T" '{}' > /dev/null

# Two transfers that go on hold, one each way, so the held-transfers screen
# has something real on it.
#   Maya buys a bike from Sam, whose account is brand new: held for a day.
#   Lena, a new client, pays Maya for a poster: held for an hour.
SAM_T=$(register "$SAM" "Sam Rivera")
SAM_W=$(open_account "$SAM_T" USER_WALLET "Main")
LENA_T=$(register "$LENA" "Lena Fischer")
LENA_W=$(open_account "$LENA_T" USER_WALLET "Main")
api POST "/api/v1/accounts/$LENA_W/deposits" "$LENA_T" '{"amountMinorUnits":90000,"currency":"USD"}' > /dev/null
api POST /api/v1/transfers "$MAYA_T" "{\"sourceAccountId\":\"$EVERYDAY\",\"destinationAccountId\":\"$SAM_W\",\"amountMinorUnits\":42000,\"currency\":\"USD\",\"description\":\"Second-hand bike\"}" > /dev/null
api POST /api/v1/transfers "$LENA_T" "{\"sourceAccountId\":\"$LENA_W\",\"destinationAccountId\":\"$EVERYDAY\",\"amountMinorUnits\":35000,\"currency\":\"USD\",\"description\":\"Poster design\"}" > /dev/null

echo "==> Checking the books"
"${PSQL[@]}" -A -t <<'SQL'
SELECT 'ledger sum (must be 0): ' || coalesce(sum(amount), 0) FROM ledger_entries;
SELECT 'accounts whose balance disagrees with their entries (must be 0): ' || count(*)
FROM account_balances b
LEFT JOIN (SELECT account_id, sum(amount) AS s FROM ledger_entries GROUP BY account_id) e ON e.account_id = b.account_id
WHERE b.balance <> coalesce(e.s, 0);
SELECT 'money on hold not matched by the hold account (must be 0): '
    || (SELECT balance FROM account_balances WHERE account_id = '00000000-0000-7000-8000-000000000003')
     - (SELECT coalesce(sum(amount), 0) FROM transfer_holds WHERE status = 'HELD');
SQL

cat <<DONE

Done. Open $BASE and sign in:

  Maya Chen      $MAYA      (three wallets and a design studio)
  Daniel Okafor  $DANIEL    (her friend and client)
  password for both: $PASS
DONE
