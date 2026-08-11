# ADR-002: Transactional outbox for event publishing

Status: accepted (Phase 5)

## Context

Payment events must reach Kafka consumers (fraud, notifications,
analytics), but a database commit and a Kafka send cannot be made atomic:
either can fail after the other succeeded.

## Decision

Events are written as rows in `outbox_events` inside the same database
transaction as the state change. A poller publishes PENDING rows to Kafka
(`FOR UPDATE SKIP LOCKED` batches, per-row exponential backoff, FAILED
parking after 10 attempts) and marks them PUBLISHED afterwards.

## Alternatives considered

- **Direct dual write** (commit, then send): drops events on crash between
  the two, or announces rolled-back payments if sent first. Rejected: this
  is the bug the pattern exists to fix.
- **CDC (Debezium)**: same pattern, log-based transport, higher
  throughput; but adds Kafka Connect + replication slot operations. The
  500ms poll meets latency needs at this scale, and swapping transports
  later changes no producer/consumer code.

## Consequences

- Delivery is at-least-once by construction (send happens before the
  PUBLISHED mark commits). Every consumer dedupes via `processed_events`
  in its own transaction. Exactly-once is never claimed.
- Kafka can be fully down without affecting the money path; proven by a
  broker-paused integration test.
