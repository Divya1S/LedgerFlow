# Security

## Authentication and sessions

- Registration stores bcrypt hashes (cost 10); plaintext never touches
  disk or logs. Proven: `passwordsAreStoredAsBcryptHashes` in AuthFlowIT.
- Login issues a stateless JWT (HS256, 60 min TTL, issuer/subject/role
  claims). Single-issuer monolith justifies a shared secret; a service
  split would move to RS256 so consumers hold only the public key.
- The secret comes from `JWT_SECRET` (env / Kubernetes Secret); the config
  refuses to start with a secret shorter than 32 bytes. The dev default in
  application.yml is dev-only and overridden everywhere else.
- Login failures return the same BAD_CREDENTIALS error for unknown email
  and wrong password, so the API does not confirm which emails exist.

## Authorization

- Role-based: USER/ADMIN from the JWT role claim, enforced by Spring
  Security (`/actuator/**` beyond health/prometheus is ADMIN only).
- Resource-level: every account/payment/transaction read checks ownership
  and returns **404, not 403**, for other users' resources, so resource
  existence is not disclosed. Proven across AccountLifecycleIT,
  MoneyFlowIT, FraudIT.
- Refunds require the merchant (recipient) or an admin, never the payer.

## Input handling and SQL

- Bean Validation on every request DTO (amount bounds, currency pattern,
  size limits); malformed bodies get a stable 400 shape.
- 100% of SQL goes through `JdbcClient` bound parameters. There is no
  string-concatenated SQL anywhere in the codebase. The
  `sqlInjectionAttemptsAreStoredAsLiterals` test stores an injection
  payload and proves it lands as inert data.
- Error responses never leak internals: unexpected exceptions log with the
  correlation id and return a generic 500 body.

## Abuse controls

- Per-user fixed-window rate limit (30/min default) on money-writing
  endpoints, counted in Redis, fail-open (a Redis outage must not become a
  write outage; correctness never depended on throttling).
- Idempotency keys are scoped per user and endpoint: one user cannot
  replay or collide with another user's keys.

## Secrets and least privilege

- No secret is committed: compose uses a dev-only password, everything
  else reads env vars; Kubernetes manifests reference Secrets (Phase 9).
- Payment methods store only external vault tokens and last-four digits,
  never PANs.
- Audit trail (`audit_logs`) is append-only at the database level and
  written in the same transaction as the audited change: actor, action,
  resource, before/after state, correlation id. Login, registration,
  account changes and every money movement are audited.
