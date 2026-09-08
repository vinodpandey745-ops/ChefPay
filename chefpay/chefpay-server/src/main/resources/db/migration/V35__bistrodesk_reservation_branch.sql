-- Bistrodesk Phase 2: Reservation gains a branch column, closing the last of the three gaps this
-- codebase's own architecture review flagged ("Menu/Customer/Reservation/Supplier have no branch
-- column at all") that's in scope for this phase (Reservation) - Menu is Phase 3's job, Customer is
-- deliberately staying branch-agnostic per Phase 7's plan, Supplier is out of scope entirely.
-- Nullable and additive: a reservation with no branch is treated as shared/not-yet-assigned,
-- visible to every branch - see Reservation.branch's javadoc.

ALTER TABLE reservation ADD COLUMN branch_id CHAR(36);

ALTER TABLE reservation ADD CONSTRAINT fk_reservation_branch FOREIGN KEY (branch_id) REFERENCES branch(id);

-- Backfill, most-precise-first:
-- 1) A reservation already linked to a table can be backfilled unambiguously from that table's own
--    floor's branch, regardless of how many branches this install has - the table itself already
--    says exactly where this booking is. Portable correlated-subquery form (works on both
--    PostgreSQL and MySQL 5.7+/8+; MySQL's "can't specify target table for update in FROM clause"
--    restriction applies to joining the updated table in a FROM clause, not to a scalar subquery in
--    the SET list, so this is safe on both).
UPDATE reservation
SET branch_id = (
    SELECT f.branch_id
    FROM restaurant_table t
    JOIN floor f ON f.id = t.floor_id
    WHERE t.id = reservation.table_id
)
WHERE reservation.table_id IS NOT NULL;

-- 2) A table-less reservation (phone booking, no table assigned yet) has nothing to derive a
--    branch from - same ambiguity as Bistrodesk Phase 2's Inventory backfill. Only safe to guess
--    for a single-branch install (identical to before this migration); a genuinely multi-branch
--    install's table-less rows are left at branch_id NULL (shared/not-yet-assigned) rather than
--    guessed at, and should be reviewed/assigned by an admin.
UPDATE reservation
SET branch_id = (SELECT id FROM branch LIMIT 1)
WHERE branch_id IS NULL AND (SELECT COUNT(*) FROM branch) = 1;
