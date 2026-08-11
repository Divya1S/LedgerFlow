# Resume Bullets

Quantified, ATS-friendly, every number real and reproducible from the
repo. Pick 3 or 4 per application; lead with the one matching the job
description.

## Core (use these first)

- Built LedgerFlow, a distributed payments platform (Java 21, Spring
  Boot, PostgreSQL, Kafka, Redis) with a double-entry ledger whose
  zero-sum invariant is enforced by database triggers; proved
  correctness with 1,000 concurrent transfers against a single account
  producing exact balances and zero double spends.
- Chaos-tested the platform by killing Kafka and Redis under load:
  102,110 requests with 0 failures; diagnosed and fixed a 25s tail
  latency (client timeout ahead of a fail-open breaker) and a 65k-event
  outbox backlog (publisher throughput), cutting worst-case latency to
  533ms.
- Optimized PostgreSQL queries on a 9.6M-row ledger with EXPLAIN
  ANALYZE: statement reads from 403ms to 0.48ms via covering indexes,
  deep pagination from 421ms to 0.86ms by replacing OFFSET with keyset
  cursors, and monthly partitioning with verified partition pruning.
- Integrated a Gemini-powered fraud analyst using function calling over
  read-only tools, with a 12-scenario golden eval measuring risk-level
  accuracy and prompt-injection resistance; the LLM layer is optional by
  design and cannot mutate financial state.
- Implemented exactly-once payment semantics for clients via
  database-enforced idempotency keys (claim, operation and response
  committed atomically), verified under concurrent duplicate requests.

## Supporting (rotate in as relevant)

- Designed a transactional outbox to Kafka with at-least-once delivery,
  consumer-side dedup, retries and dead-letter topics; payments stay
  correct through complete broker outages (verified by pausing the
  broker mid-payment in integration tests).
- Achieved 688 req/s across mixed workloads on a laptop with p95 of
  103ms for ACID transfers, including a hot-account contention scenario
  costing only ~2ms extra at p95 due to deterministic lock ordering.
- Shipped 45 automated tests (92.5 percent instruction coverage) running
  against real PostgreSQL, Kafka and Redis via Testcontainers, including
  concurrency storms, deadlock reproduction and failure injection.
- Deployed with Helm to Kubernetes (probes, HPA, secrets), authored an
  AWS reference stack in Terraform (EKS, Multi-AZ RDS, MSK, ElastiCache),
  and hardened CI with CodeQL, Trivy image scanning and coverage gates.
- Executed a zero-downtime schema migration (expand/backfill/contract
  with a dual-write trigger) with 120/120 requests succeeding during the
  live schema change.

## One-liner for the projects section header

LedgerFlow: chaos-tested double-entry payments platform (Java,
PostgreSQL, Kafka, Redis, Gemini) with measured performance and
database-enforced correctness. github.com/Divya1S/LedgerFlow
