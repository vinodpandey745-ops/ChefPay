-- Bistrodesk branch-isolation release (requirement #3, user-confirmed decision: "strictly branch
-- specific, no shared option" - the same call as V39's Customer/Supplier reversal). InventoryItem
-- already has a nullable branch_id column since V34, but that migration deliberately left a
-- pre-existing multi-branch install's rows at branch_id NULL as a legitimate "shared/not-yet-
-- assigned" resting state - see V34's own comment. This release closes that: a null-branch item is
-- exactly the "inventory added at branch A is visible/editable from branch B" bug the user reported,
-- so every row is now backfilled, matching V39's unconditional approach rather than V34/V35's
-- single-branch-only guard.
--
-- Deterministic choice of branch: the install's oldest branch by created_at (falls back to a single
-- branch trivially when there's only one) - an admin can reassign an individual item afterward via
-- the ordinary Inventory update endpoint once this is applied. No column/constraint change here -
-- branch_id and its FK already exist from V34; mandatory-ness from this point on is enforced at the
-- application layer (InventoryController/InventoryService), the same "logically mandatory,
-- schema-nullable" convention V39's own comment describes for Customer/Supplier.

UPDATE inventory_item
SET branch_id = (SELECT id FROM branch ORDER BY created_at ASC LIMIT 1)
WHERE branch_id IS NULL;
