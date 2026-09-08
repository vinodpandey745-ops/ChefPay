-- Bistrodesk Phase 2: real Order -> Branch FK, closing the gap OrderRepository has documented since
-- Round 11 ("Order has no direct branch column of its own... every non-table order is deliberately
-- included regardless of the branch filter, since there's nothing to filter it by yet"). Nullable
-- and additive only - every existing order (dine-in or not) keeps working exactly as before via
-- Order.getEffectiveBranch()'s table->floor->branch fallback; only NEW orders populate this column
-- directly, including non-table orders for the first time ever.

ALTER TABLE customer_order ADD COLUMN branch_id CHAR(36);

ALTER TABLE customer_order ADD CONSTRAINT fk_order_branch FOREIGN KEY (branch_id) REFERENCES branch(id);
