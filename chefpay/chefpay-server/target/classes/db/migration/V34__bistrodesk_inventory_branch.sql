-- Bistrodesk Phase 2: InventoryItem gains a branch column, closing the gap flagged in this
-- codebase's own architecture review ("Menu/Customer/Reservation/Supplier have no branch column at
-- all") - Inventory belongs in that same list. Nullable and additive: an item with no branch is
-- treated everywhere (InventoryService/InventoryController) as a shared/not-yet-assigned item,
-- visible to every branch - the same "empty/null = works everywhere" convention already used for
-- AppUser.branches and Order.branch.

ALTER TABLE inventory_item ADD COLUMN branch_id CHAR(36);

ALTER TABLE inventory_item ADD CONSTRAINT fk_inventory_item_branch FOREIGN KEY (branch_id) REFERENCES branch(id);

-- Backfill: a single-branch install (the overwhelming common case, and every install before this
-- migration) gets every existing item auto-assigned to its one Branch - zero behavior change,
-- since a lone branch's "unique per branch" constraint below is identical to the old global-unique
-- constraint it replaces. A pre-existing MULTI-branch install is deliberately left at branch_id
-- NULL instead of guessed at - there is no reliable way to infer which branch's physical stockroom
-- an already-existing row belongs to from the data alone - and its items fall into the shared/
-- not-yet-assigned bucket until an admin assigns each one to its correct branch. This UPDATE is
-- plain, portable SQL (a scalar subquery + a WHERE count check), no dialect-specific syntax.
UPDATE inventory_item
SET branch_id = (SELECT id FROM branch LIMIT 1)
WHERE (SELECT COUNT(*) FROM branch) = 1;

-- The old global UNIQUE(name) constraint must go - two branches legitimately stocking an item of
-- the same name (e.g. "Rice" at both Branch A and Branch B, separate physical stockrooms/ledgers)
-- would otherwise collide on the very first insert. Replaced with a composite unique constraint
-- scoped to (branch_id, name), enforced at the DB level for any item that already has a branch
-- assigned; the not-yet-assigned bucket (branch_id IS NULL) relies on InventoryService's own
-- existsByNameIgnoreCaseAndBranchIsNull check instead, since standard SQL treats every NULL as
-- distinct from every other NULL in a unique constraint (so the DB alone can't police that bucket).
--
-- Portability note (same caveat as V31's Subscription migration): DROP CONSTRAINT is PostgreSQL
-- syntax. A MySQL deployment needs the equivalent `ALTER TABLE inventory_item DROP INDEX
-- uq_inventory_item_name;` swapped in before this file runs - not verified against a live MySQL
-- instance in this sandbox (no working DB connection here - see project README "Build status").
ALTER TABLE inventory_item DROP CONSTRAINT IF EXISTS uq_inventory_item_name;

ALTER TABLE inventory_item ADD CONSTRAINT uq_inventory_item_branch_name UNIQUE (branch_id, name);
