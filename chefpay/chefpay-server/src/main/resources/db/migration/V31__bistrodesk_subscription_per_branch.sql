-- Bistrodesk Phase 1: Subscription moves from per-restaurant to per-branch (a user-confirmed,
-- deliberately more invasive choice over the recommended per-restaurant default - see
-- Subscription.java's javadoc for the full reasoning and PlatformOwnerController for the new
-- branch-scoped read/write API).
--
-- subscription.restaurant_id itself is deliberately LEFT IN PLACE, still NOT NULL, and still
-- populated on every insert (via branch.getRestaurant() - see Subscription.restaurant's own
-- @Deprecated javadoc) rather than dropped in this same migration: dropping a column is a
-- destructive, dialect-fiddly, hard-to-reverse operation, and there is no correctness need to do it
-- now that every insert still supplies a value for it. A follow-up migration can drop it, once this
-- change has run cleanly in production for a while.
--
-- new branch_id IS left NULLABLE (not NOT NULL) on purpose, matching the exact same "nullable now,
-- backfilled on next boot, enforced at the application layer instead of the schema" convention
-- branch.branch_code already established (V28) - see DataSeeder#ensurePhase2Backfill, which
-- populates one Subscription per Branch on the very next application boot after this migration
-- runs, and Subscription.branch's own javadoc for why a schema-level NOT NULL here would risk
-- failing on whichever database engine doesn't accept adding a NOT NULL column to a populated table
-- without an explicit DEFAULT.

ALTER TABLE subscription ADD COLUMN branch_id CHAR(36);

ALTER TABLE subscription ADD CONSTRAINT fk_sub_branch FOREIGN KEY (branch_id) REFERENCES branch(id);

-- NULL branch_id values are distinct from one another under a UNIQUE constraint on every mainstream
-- SQL engine (Postgres, MySQL 8+, SQLite), so this is safe to add immediately even before the
-- backfill runs - multiple pre-backfill rows with a NULL branch_id do not violate uniqueness; once
-- backfilled, each branch's exactly-one-subscription invariant is enforced same as before.
ALTER TABLE subscription ADD CONSTRAINT uq_subscription_branch UNIQUE (branch_id);

-- CRITICAL, dialect-specific, and the one statement in this file NOT verified end to end (this
-- sandbox has no working mvn/JVM and no live Postgres/MySQL to actually run a migration against -
-- see every prior "Round 27"-style report in this project for that standing limitation): the OLD
-- V29 constraint below enforced "at most one Subscription per Restaurant," which structurally
-- CANNOT hold once a single (single-tenant) Restaurant has more than one Branch each with its own
-- Subscription row sharing that same restaurant_id - so it must be dropped, or a second branch's
-- subscription insert will fail with a unique-constraint violation.
--
-- The statement below is PostgreSQL syntax (this app's primary configured production dialect).
-- MySQL does not accept `DROP CONSTRAINT` for a plain UNIQUE key created via `CONSTRAINT ... UNIQUE`
-- - it needs `ALTER TABLE subscription DROP INDEX uq_subscription_restaurant;` instead. Any install
-- actually running the `mysql` Spring profile MUST swap this one line for that MySQL form (and
-- verify it against a real MySQL instance) before enabling more than one branch's subscription -
-- flagged here deliberately rather than silently guessing a MySQL syntax variant this sandbox
-- cannot verify.
ALTER TABLE subscription DROP CONSTRAINT IF EXISTS uq_subscription_restaurant;
