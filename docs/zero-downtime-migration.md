# Zero-Downtime Migration: a rename executed live

Renaming `users.full_name` to `users.display_name` with the application
serving traffic throughout. Not a thought experiment: the sequence below
was executed against the running dev stack, with a request loop hammering
the old instance during the schema change.

## Why a rename is the hard case

`ALTER TABLE ... RENAME COLUMN` is instant in PostgreSQL, but the moment
it commits, every still-running instance of the old code breaks. Any
schema change that old and new code cannot BOTH live with must be split
into expand and contract steps around the code deployment.

## The executed sequence

**1. Expand ([V3](../src/main/resources/db/migration/V3__add_users_display_name.sql))**
while old code serves:

- `ADD COLUMN display_name TEXT` (nullable: adding a nullable column takes
  only a brief metadata lock)
- a trigger mirroring `full_name` and `display_name` both ways, so
  whichever column the running code version writes, both stay coherent
- batched backfill (10,000 rows per statement, so a big table never holds
  long locks or one giant transaction)

**2. Deploy new code** that writes and reads `display_name` (the API field
name stays `fullName`; clients see no change). Old and new instances
coexist safely: the trigger bridges them.

**3. Contract ([V4](../src/main/resources/db/migration/V4__finish_users_display_name.sql))**
after every instance runs new code: `SET NOT NULL`, drop the trigger,
drop `full_name`. On a large table the NOT NULL would be introduced as a
`NOT VALID` check constraint plus `VALIDATE CONSTRAINT` to avoid a
full-table scan under exclusive lock.

## What the live run showed

- 120 registrations were fired at the OLD instance while V3 applied:
  **120/120 returned 201.** No request observed the migration.
- After V3, the old code (writing `full_name`) and the new code (writing
  `display_name`) ran side by side; spot checks confirmed the trigger
  left zero NULLs in either column.
- The new instance's very first request returned one 500 during its
  startup second. That instance had not passed readiness yet; in
  Kubernetes the readiness probe gates traffic exactly for this window,
  which is why the probe exists. No partial state was left; the retry
  succeeded.
- After V4, `full_name` is gone, new registrations work, and a user
  created before the whole exercise still logs in.

## Rules this demonstrates

1. Never ship a schema change and the code that requires it in one step;
   ship schema the old code tolerates, then code, then cleanup.
2. Additive first: nullable column, dual-write bridge, backfill in
   batches, only then constrain and drop.
3. Every migration is forward-only and in version control (Flyway);
   "rollback" means shipping a new expand step, not editing history.
