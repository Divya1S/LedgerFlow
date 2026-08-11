# ADR-007: Keyset pagination with opaque cursors

Status: accepted (Phase 4)

## Context

Transaction history is unbounded and read newest-first. OFFSET pagination
generates and discards every skipped row.

## Decision

All listings paginate by keyset: `(created_at, id) < cursor ORDER BY
created_at DESC, id DESC LIMIT n`, with the cursor base64-encoded and
opaque to clients. UUIDv7 ids make the tiebreaker natural. The statement
endpoint's cursor additionally carries the running balance so later pages
continue it without re-summing history.

## Evidence

Measured at depth 500,000 on the 5M-row dataset: OFFSET 421ms (and
spilling to temp files) vs keyset 0.86ms, both fully indexed
(docs/query-optimization.md, Case 4). Keyset is also stable under
concurrent inserts, where OFFSET skips or repeats rows.

## Consequences

- No "jump to page 37"; product-wise, infinite scroll and date filters
  cover the real use cases.
- Sort orders must match an index; the V2 indexes encode the supported
  orderings.
