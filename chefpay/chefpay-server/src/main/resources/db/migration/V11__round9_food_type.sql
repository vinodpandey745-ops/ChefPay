-- Round 9: precise veg / egg / non-veg food-type classification on menu_item, driving the
-- green/yellow/red indicator strip on order-taking menu tiles. Same portability caveat as
-- V1-V10: written against the JPA mapping but not run against a live PostgreSQL/MySQL instance
-- in the environment this was authored in.
--
-- NOTE: this project runs against SQLite by default (see application.yml, profile "dev"), which
-- has no ALTER TABLE ... ALTER COLUMN ... SET NOT NULL support - unlike V8/V9's plain boolean
-- additions, this column needs a computed (not constant) backfill, so instead of the
-- add-nullable/backfill/alter-to-NOT-NULL sequence, the column is added NOT NULL with a literal
-- DEFAULT (satisfying the constraint for every existing row immediately, exactly like V9's
-- direct_sale column) and then backfilled in a separate UPDATE - portable across SQLite,
-- PostgreSQL, and MySQL alike.
ALTER TABLE menu_item ADD COLUMN food_type VARCHAR(16) NOT NULL DEFAULT 'VEG';

UPDATE menu_item SET food_type = CASE WHEN vegetarian THEN 'VEG' ELSE 'NON_VEG' END;
