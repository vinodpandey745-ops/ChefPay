-- Bistrodesk Phase 3: MenuItem gains an optional branch column (requirement #5, centralized vs.
-- branch-specific menu). Nullable and additive, deliberately with NO backfill: null means "shared/
-- centralized" (sold at every branch), which is exactly what every existing item's behavior already
-- is today - a pre-existing install's menu is, by definition, the one and only menu every branch
-- has ever seen, so leaving every row at branch_id NULL is not just safe but the CORRECT read of
-- "what this data has always meant," unlike Bistrodesk Phase 2's Inventory/Reservation migrations
-- (which needed a real backfill because physical stock/bookings *do* already belong to one real
-- branch that had to be inferred). See MenuItem.branch's javadoc for the full visibility rule.

ALTER TABLE menu_item ADD COLUMN branch_id CHAR(36);

ALTER TABLE menu_item ADD CONSTRAINT fk_menu_item_branch FOREIGN KEY (branch_id) REFERENCES branch(id);

-- Restaurant.menuCentralized - a NOT NULL boolean, so (unlike the nullable branch_id above) this
-- needs an explicit default for every pre-existing row. Defaults true (today's only real behavior -
-- one shared menu) for every existing install, matching the entity's own @Builder.Default.
ALTER TABLE restaurant ADD COLUMN menu_centralized BOOLEAN NOT NULL DEFAULT TRUE;

