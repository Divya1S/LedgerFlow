# ADR-003: Kafka for asynchronous events

Status: accepted (Phase 5)

## Context

Fraud scoring, notifications and analytics must not sit inside the money
transaction: they have different latency, failure and scaling profiles.

## Decision

Kafka carries domain events (`payment.events`, `ledger.events`, ...) from
the outbox to independent consumer groups. Partitions are keyed by
aggregate id, preserving per-aggregate order. Consumer failure policy:
3 retries then `<topic>.DLT`.

## Why Kafka over alternatives

- Multiple independent consumers of the same stream (fraud AND
  notifications AND analytics) is Kafka's core model; queues (RabbitMQ/
  SQS) model competing consumers and need fan-out topology bolted on.
- Replayable log: a new consumer (or a fixed one after a DLT incident)
  can reprocess history.
- Consumer-group rebalancing gives horizontal scaling without
  coordination code.

## Consequences

- One more stateful system to operate; acceptable because the money path
  never depends on it (see ADR-002).
- Ordering is per-partition only; no consumer may assume global order.
