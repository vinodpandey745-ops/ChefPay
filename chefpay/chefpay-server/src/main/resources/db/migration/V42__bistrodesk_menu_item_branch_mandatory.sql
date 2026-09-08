-- Bistrodesk branch-isolation release (requirement #1, user-confirmed decision: "strictly for the
-- branch where it is getting created, not shared with any other branch" - the same call as V39's
-- Customer/Supplier reversal and V41's Inventory reversal). MenuItem already has a nullable
-- branch_id column since V36, but that migration deliberately left EVERY row at branch_id NULL as
-- the "shared/centralized, sold at every branch" resting state - see V36's own comment. This
-- release closes that: a null-branch item is exactly the "menu item created at branch A shows up
-- at branch B" bug the user reported, so every row is now backfilled, matching V39/V41's
-- unconditional approach.
--
-- Deterministic choice of branch: the install's oldest branch by created_at (falls back to a single
-- branch trivially when there's only one) - an admin can reassign an individual item afterward via
-- the ordinary Menu Editor update endpoint once this is applied. No column/constraint change here -
-- branch_id and its FK already exist from V36; mandatory-ness from this point on is enforced at the
-- application layer (MenuController), the same "logically mandatory, schema-nullable" convention
-- V39/V41 already describe for Customer/Supplier/InventoryItem.

UPDATE menu_item
SET branch_id = (SELECT id FROM branch ORDER BY created_at ASC LIMIT 1)
WHERE branch_id IS NULL;
