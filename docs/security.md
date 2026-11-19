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
- Every value goes through `JdbcClient` bound parameters. Some queries
  are assembled from parts (optional search filters, a shared expression
  for naming the other party), and those parts are fixed strings in the
  code. Nothing a client sends is ever concatenated into SQL. The
  `sqlInjectionAttemptsAreStoredAsLiterals` test stores an injection
  payload and proves it lands as inert data.
- Search text is matched literally: `%` and `_` typed by a person are
  escaped before they reach `ILIKE`.
- Invalid query parameters (a bad date, an unknown filter value) are
  answered with a 400 and the field at fault, not a 500.
- Error responses never leak internals: unexpected exceptions log with the
  correlation id and return a generic 500 body.

## What other people can learn about you

- Looking up an account id returns a display name, the account type and
  whether it is closed. No balance, no email, no other accounts. A wallet
  is shown by its owner's name, never by the owner's private label for it.
- A money request shows the payer who is asking, the amount and the note.
  The receiving account id is not exposed, and the pay endpoint checks
  ownership of the source account before loading the request so that
  error codes cannot be used to work it out.
- Fraud verdicts and analyst notes are shown to the merchant who received
  the payment and to admins, never to the payer.
- The activity feed returns only rows the caller acted in, and only the
  action, time and reference. Before and after state stays internal.

## Protecting the person sending

- A first payment of $200 or more to someone the sender has never
  settled money with is held, and can be cancelled until it is delivered
  (ADR-010). The sender cannot skip the hold: the point is to outlast
  the phone call a scammer keeps them on.
- The hold applies inside the one transfer path, so scheduled transfers
  and money request links cannot be used to go around it.
- The reasons for a hold are shown to the sender only. The recipient
  sees that money is coming and when.
- A saved recipient is not a trusted one. Only a payment that actually
  arrived makes later payments instant.

## Protecting an account

- Changing a password needs the current one, so a borrowed, still
  signed-in browser is not enough to take a profile over. Tokens already
  issued stay valid until they expire (they are stateless), which is why
  their lifetime is short.
- Freezing an account stops money leaving it without closing it. Only the
  owner (or an admin) can freeze or unfreeze.
- A merchant cannot close a dispute in its own favour. Declining passes
  the decision to a platform admin.
- CSV exports defuse spreadsheet formulas: text starting with `=`, `+`,
  `-` or `@` is written as text, so a hostile description cannot run when
  the file is opened.
- The dashboard keeps the sign-in token in `sessionStorage`: it survives a
  page reload and ends when the tab closes. That is readable by any script
  on the page, so the page loads no third-party scripts or fonts.

## Abuse controls

- Per-user fixed-window rate limit (30/min default) on money-writing
  endpoints (including paying a money request and deciding a dispute),
  counted in Redis, fail-open (a Redis outage must not become a
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
