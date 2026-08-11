# ADR-009: Modular monolith, not microservices

Status: accepted (Phase 1)

## Context

The system decomposes logically into identity, accounts, money movement,
ledger, queries, outbox, fraud, notifications, analytics. The fashionable
default is one service each.

## Decision

One deployable Spring Boot application with bounded contexts as packages.
Hard rules: contexts touch each other only through domain services and
events; fraud/notifications/analytics consume Kafka only and never join
the money transaction.

## Why

- The core write path (validate, lock, ledger, balances, audit, outbox)
  must be ONE database transaction. Splitting it across services trades
  a `COMMIT` for a distributed saga with compensation logic: enormous
  complexity, weaker guarantees, zero benefit at this scale.
- The parts that genuinely want independence are already asynchronous
  behind Kafka. Extracting them later is packaging work (their only
  coupling is topics + the shared database schema for their own tables),
  not a redesign.
- One deployable means the concurrency and failure semantics proven in
  tests are the production semantics.

## Consequences

- Service boundaries are enforced mechanically by ArchUnit rules
  (BoundaryRulesTest): private persistence layers, no controller
  imports across contexts, and async consumers unable to depend on
  money-path contexts. The build fails on a boundary breach.
- A future extraction order is documented: fraud first (pure consumer),
  then notifications/analytics, with identity last (shared JWT secret
  becomes RS256).
