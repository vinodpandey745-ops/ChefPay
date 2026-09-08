-- Bistrodesk Phase 3 (requirement #6): Tax and Discount each gain an optional branch column, with
-- NO backfill needed - null already means exactly what every existing row's behavior has always
-- been (a single GLOBAL rate/preset used by every branch), same reasoning as V36's menu_item.
-- branch_id (unlike V34/V35's Inventory/Reservation migrations, which needed a real backfill
-- because that data already belonged to one physical branch).
--
-- User-confirmed decision (via an explicit clarifying question before this was built): branch-level
-- overrides only - no third "state"/region tier. See Tax.branch's javadoc for the full resolution
-- rule (BillingService#resolveTax/#resolveDefaultTax).

ALTER TABLE tax_rate ADD COLUMN branch_id CHAR(36);

ALTER TABLE tax_rate ADD CONSTRAINT fk_tax_rate_branch FOREIGN KEY (branch_id) REFERENCES branch(id);

-- The old global UNIQUE(code) no longer fits: the same code must now be able to appear once
-- globally (branch_id NULL) and, separately, once per overriding branch. Replaced below with a
-- composite UNIQUE(code, branch_id) - safe because every mainstream SQL engine (Postgres, MySQL 8+,
-- SQLite) treats NULL branch_id values as distinct from one another, so multiple pre-existing global
-- rows and any number of per-branch overrides of the same code can coexist under the new key exactly
-- as the single-tier V4 constraint always allowed for its one global row.
--
-- PostgreSQL syntax below (this app's primary configured production dialect) - see V31's identical
-- caveat: MySQL does not accept `DROP CONSTRAINT` for a plain UNIQUE key, it needs
-- `ALTER TABLE tax_rate DROP INDEX uq_tax_rate_code;` instead. Any install running the `mysql`
-- Spring profile MUST swap this one line for that MySQL form (and verify it against a real MySQL
-- instance, since this sandbox has no working mvn/JVM or live database to test migrations against).
ALTER TABLE tax_rate DROP CONSTRAINT IF EXISTS uq_tax_rate_code;

ALTER TABLE tax_rate ADD CONSTRAINT uq_tax_rate_code_branch UNIQUE (code, branch_id);

-- Discount has no uniqueness constraint to touch (confirmed above, V4) - branch_id here is pure
-- visibility/eligibility scoping (BillingService#applyDiscount's BRANCH_MISMATCH check), not a
-- code-based override resolution like Tax, so this column alone is enough.
ALTER TABLE discount ADD COLUMN branch_id CHAR(36);

ALTER TABLE discount ADD CONSTRAINT fk_discount_branch FOREIGN KEY (branch_id) REFERENCES branch(id);
