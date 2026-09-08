-- Bistrodesk branch-isolation release (requirement #6, user-confirmed decision: "strictly mandatory
-- branch, no shared option"). Customer and Supplier previously had NO branch column at all - see
-- V35's own comment ("Customer is deliberately staying branch-agnostic ... Supplier is out of scope
-- entirely") for the prior design this migration explicitly reverses. Both entities now belong to
-- exactly one branch, mirroring Inventory/Reservation's branch_id + FK shape.
--
-- Unlike V34 (Inventory) and V35 (Reservation), whose single-branch-only backfill deliberately left
-- a pre-existing multi-branch install's rows at branch_id NULL (a legitimate "shared/not-yet-
-- assigned" resting state for those entities), Customer and Supplier have no such resting state per
-- the user's decision - every row, on every install shape, is backfilled onto one real branch below.
-- Deterministic choice of branch: the install's oldest branch by created_at (falls back to a single
-- branch trivially when there's only one) - an admin can reassign individual rows afterward via the
-- ordinary Customer/Supplier update endpoints once this is applied.

ALTER TABLE customer ADD COLUMN branch_id CHAR(36);
ALTER TABLE customer ADD CONSTRAINT fk_customer_branch FOREIGN KEY (branch_id) REFERENCES branch(id);

ALTER TABLE supplier ADD COLUMN branch_id CHAR(36);
ALTER TABLE supplier ADD CONSTRAINT fk_supplier_branch FOREIGN KEY (branch_id) REFERENCES branch(id);

-- Unconditional backfill (portable correlated-subquery form, same as V35's) - every branchless row,
-- on any install shape, is assigned the oldest branch. No COUNT(*) = 1 guard the way V34/V35 have,
-- because leaving a row NULL here is not an acceptable end state for this release.
UPDATE customer
SET branch_id = (SELECT id FROM branch ORDER BY created_at ASC LIMIT 1)
WHERE branch_id IS NULL;

UPDATE supplier
SET branch_id = (SELECT id FROM branch ORDER BY created_at ASC LIMIT 1)
WHERE branch_id IS NULL;

-- Supplier's old global UNIQUE(name)-equivalent check was only ever enforced in application code
-- (SupplierService#existsByNameIgnoreCase) with no DB constraint - SupplierService now checks
-- per-branch (existsByNameIgnoreCaseAndBranch_Id) instead, no schema constraint change needed here.
-- Customer never had a uniqueness constraint on name/phone at all (see Customer's own javadoc on
-- why phone is intentionally non-unique) - unaffected by this migration.
