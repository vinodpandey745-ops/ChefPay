package com.chefpay.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.math.BigDecimal;

/**
 * A stock-tracked ingredient/supply (Phase 5, ARCHITECTURE.md §13). Deliberately a standalone
 * stockroom ledger, not wired to {@link MenuItem} as a bill-of-materials/recipe yet - "does
 * selling one Chicken Biryani deduct N grams of rice" is a real feature but a separate, larger
 * piece of scope (recipe definitions, partial-batch handling, unit conversion) than this drop's
 * "track stock levels, receive/adjust/waste it, flag what's running low" slice. All movement goes
 * through {@link InventoryTransaction} rather than editing {@code quantityOnHand} directly, so
 * there's always an auditable reason for every change - see {@code InventoryService}.
 */
@Entity
@Table(name = "inventory_item")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
@EqualsAndHashCode(callSuper = false)
public class InventoryItem extends BaseEntity {

    /** Bistrodesk Phase 2: was globally unique - a chain's Branch A and Branch B could never both
     * stock an item named "Rice" under separate stockroom ledgers. Uniqueness is now scoped by
     * {@link #branch} instead (enforced by the DB via a composite constraint, and re-checked in
     * {@code InventoryService} since a null branch can't rely on the DB's "NULLs are distinct"
     * behavior for the not-yet-assigned bucket below). */
    @Column(nullable = false)
    private String name;

    /** Free-form unit label ("kg", "ltr", "pcs", "box") - no unit-conversion engine in this drop. */
    @Column(nullable = false, length = 20)
    private String unit;

    @Column(nullable = false, precision = 14, scale = 3)
    @Builder.Default
    private BigDecimal quantityOnHand = BigDecimal.ZERO;

    /** At or below this, {@code InventoryService.listLowStock} surfaces the item. Null = no alerting for this item. */
    @Column(precision = 14, scale = 3)
    private BigDecimal reorderThreshold;

    @Column(precision = 12, scale = 2)
    private BigDecimal costPerUnit;

    @Builder.Default
    private boolean active = true;

    /** Round 14 (F3.1): the supplier {@code AutoReplenishmentScheduler} orders this item from when
     * auto-generating a draft PO from a seasonality-aware suggestion - null means this item is
     * never eligible for auto-PO generation (it simply gets left out, same as an item with no
     * {@code reorderThreshold} is already left out of low-stock alerting), and a manager can still
     * order it manually via the ordinary Purchase Order screen exactly as today. Deliberately
     * optional rather than required: most items won't have this set on day one, and nothing about
     * manual replenishment (Round 12) or the read-only suggestion list ({@code
     * ReplenishmentService#generateSuggestions}) depends on it being present. */
    @ManyToOne
    @JoinColumn(name = "preferred_supplier_id")
    private Supplier preferredSupplier;

    /** Which branch's physical stockroom this item's {@link #quantityOnHand} tracks. Bistrodesk
     * Phase 2 originally allowed {@code null} ("not yet assigned", treated as shared/visible to
     * every branch); the Bistrodesk branch-isolation release reverses that (requirement #3,
     * user-confirmed decision: "strictly branch specific, no shared option" - the same call as
     * {@code Customer}/{@code Supplier}) because a null-branch item is exactly the "inventory added
     * at branch A is visible/editable from branch B" bug reported in the field. Every row is now
     * backfilled onto a real branch ({@code V41__bistrodesk_inventory_item_branch_mandatory.sql} on
     * Postgres/MySQL, {@code DataSeeder#ensureInventoryItemBranchBackfill} on SQLite) and every new
     * item is created with one via {@code InventoryController#createItem}'s mandatory {@code
     * resolveEffectiveBranchId} resolution. Nullable at the JPA/DB level only as a migration safety
     * net (mandatory-ness is enforced at the service layer, not a NOT NULL constraint - same
     * "logically mandatory, schema-nullable" convention {@code Customer#branch}/{@code
     * Supplier#branch} use) - {@code InventoryService}'s read paths still tolerate a stray
     * branchless row defensively, but real request traffic should never produce one going
     * forward. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "branch_id")
    private Branch branch;
}
