# Load and Chaos Testing

## Load test

```bash
docker compose up -d
export JAVA_HOME="$(brew --prefix openjdk@21)/libexec/openjdk.jdk/Contents/Home"

# Raise the per-user rate limit: the load test intentionally exceeds the
# production default of 30 money requests/minute per user.
RATE_LIMIT_MONEY_PER_MINUTE=1000000 java -jar target/ledgerflow-0.1.0-SNAPSHOT.jar &

mkdir -p load/results
k6 run --summary-export=load/results/summary.json load/ledgerflow-load.js
```

Scenarios (60s each, concurrent): wallet transfers across distinct
accounts (20 VUs), hot-wallet contention where 10 VUs drain one shared
wallet, payments with the fee path (10 VUs), cached history reads
(10 VUs), and bcrypt-heavy registration + account creation (3 VUs).

Real results and interpretation: [../docs/performance.md](../docs/performance.md).

## Chaos test

```bash
./chaos/chaos-test.sh
```

Runs 120s of transfer load while: Kafka is STOPPED at t=20s and restarted
at t=45s, Redis is PAUSED at t=60s and unpaused at t=85s. Afterwards it
asserts the financial invariants directly in PostgreSQL: global ledger sum
is zero, every transaction's entries sum to zero, every floor-constrained
account's balance equals its ledger sum, and the outbox drained.
