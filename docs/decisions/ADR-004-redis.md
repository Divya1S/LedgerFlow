# ADR-004: Redis for caching and rate limiting only

Status: accepted (Phase 6)

## Context

Read-heavy endpoints (history first pages) and per-user rate limiting
need shared, fast, expiring state across app instances.

## Decision

Redis holds two kinds of keys: cache-aside pages (30s TTL with jitter,
evicted after commit by the movement that invalidates them) and
fixed-window rate-limit counters. It is NEVER consulted for a financial
decision; balances used in write paths come exclusively from locked
PostgreSQL rows.

All access goes through a wrapper that fails open with a 10s breaker:
Redis down means no cache and no throttling, never an error and never a
wrong balance. Proven by pausing the Redis container mid-test.

## Rejected uses

- Authoritative balances in Redis: cache incoherence would equal monetary
  loss. Rejected outright.
- Session storage: JWTs made sessions stateless instead.
- Distributed locks: row locks in PostgreSQL are already the
  serialization point and carry transactional semantics.

## Consequences

- Cache staleness is bounded by TTL + explicit eviction; cursored pages
  bypass the cache entirely.
- Rate limiting is best-effort by design; abuse control degrades before
  correctness ever would.
