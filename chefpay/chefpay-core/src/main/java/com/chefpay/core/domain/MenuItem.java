package com.chefpay.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * Modifier groups and recipe/inventory links attach to this entity starting Phase 5 - kept out
 * for now to match the phase plan. Kitchen station routing ({@code station}) landed in Phase 3.
 */
@Entity
@Table(name = "menu_item")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
@EqualsAndHashCode(callSuper = false)
public class MenuItem extends BaseEntity {

    /** Fix (production outage): {@code @ManyToOne} defaults to EAGER when no fetch is given, which
     * meant loading ANY {@code MenuItem} - not just this one field, every single query anywhere in
     * the app that returns a {@code MenuItem}, including a plain {@code findAll()} - always
     * immediately resolved this association too. On a real production database that turned out to
     * have one menu item pointing at a {@code category_id} that no longer exists (confirmed live:
     * {@code jakarta.persistence.EntityNotFoundException: Unable to find ... MenuCategory}), that
     * meant every startup crash-looped forever inside {@code DataSeeder
     * #ensureMenuItemBranchBackfill} - a step that never even needed this field - and, worse, would
     * have thrown the exact same way from the Menu Editor, the POS ordering screen, and Reports the
     * moment any of them touched that one bad row. LAZY is safe here for the same reason it already
     * is on every other association in this codebase fixed this same way (see {@code
     * Device#lastUser}'s javadoc): every real caller (see {@code MenuController}'s DTO mapping)
     * runs inside an HTTP request thread under this app's default {@code open-in-view=true}, so the
     * lazy reference still resolves transparently there. This does not repair the bad row itself
     * (that needs a one-time data fix - see the Round 5 hotfix notes) but it means one broken
     * category reference can no longer take the whole application down, and now only fails exactly
     * where that specific item's category is actually displayed. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_id", nullable = false)
    private MenuCategory category;

    @Column(nullable = false)
    private String name;

    /** Optional stock-keeping / price-lookup codes. */
    private String sku;
    private String plu;

    private String description;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal price;

    /** Reference to a tax rule code (see Tax config, Phase 4). Null = restaurant default rate. */
    private String taxCode;

    /** Null = this item isn't routed to a specific kitchen screen (fine for a single-KDS setup). */
    @ManyToOne
    @JoinColumn(name = "station_id")
    private KitchenStation station;

    /** Kept for backward compatibility with existing code that reads/writes {@code isVegetarian()} -
     * {@link #foodType} (Round 9) is the more precise 3-way classification and is NOT derived from
     * this field or vice versa, so the two can in principle disagree if only one is updated; callers
     * that only ever set {@code vegetarian} simply keep the default {@code foodType} of {@code VEG}. */
    @Builder.Default
    private boolean vegetarian = true;

    /** Round 9: precise veg / egg / non-veg classification driving the colored indicator strip on
     * order-taking menu tiles (green/yellow/red) - see {@link FoodType}. Independent of {@link #vegetarian}
     * (see that field's comment). */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private FoodType foodType = FoodType.VEG;

    @Builder.Default
    private boolean available = true;

    @Builder.Default
    private boolean active = true;

    private String imagePath;
    private String barcode;

    /** When true, adding this item to an order never enters the kitchen workflow - it stays at
     * {@code OrderItemStatus.ADDED} for its whole life (billable, removable, editable exactly like
     * any other line) instead of being included when the order is sent to the kitchen. For items
     * that never need prep/verification (bottled drinks, packaged snacks) - see
     * {@code OrderService#sendToKitchen} for the exact exclusion and {@code KitchenService} for why
     * that alone is enough to keep it off the KDS queue (the queue only shows items that reached
     * SENT or later). */
    @Builder.Default
    private boolean directSale = false;

    /** Optional alternate price for a "half portion" of this item (e.g. Half Chicken Korma) - null
     * means this item has no half-portion option and always sells at {@link #price} (the "full"
     * price). When set, {@code OrderTakingView} offers a Full/Half choice when adding this item;
     * the chosen price is snapshotted onto {@code OrderItem#unitPriceSnapshot} exactly like the
     * full price always has been, and {@code OrderItem#modifiersSummary} records "Half" so it's
     * visible on the cart, KDS ticket, and receipt without a new column on every downstream DTO. */
    private BigDecimal halfPrice;

    /** Optional kitchen prep-time estimate in minutes, shown in Menu Editor for staff/customer
     * expectation-setting only - nothing in the kitchen/order flow reads or enforces this today
     * (no SLA timers, no KDS sort-by-prep-time). Null = not specified. */
    private Integer prepTimeMinutes;

    /** Which branch this item belongs to. Bistrodesk Phase 3 originally allowed {@code null}
     * ("shared/centralized", sold at every branch); the Bistrodesk branch-isolation release
     * reverses that (requirement #1, user-confirmed decision: "strictly for the branch where it is
     * getting created, not shared with any other branch") because a null-branch item was exactly
     * the "menu item created at branch A shows up at branch B" bug reported in the field. Every row
     * is now backfilled onto a real branch ({@code V42__bistrodesk_menu_item_branch_mandatory.sql}
     * on Postgres/MySQL, {@code DataSeeder#ensureMenuItemBranchBackfill} on SQLite) and every new
     * item is created with one via {@code MenuController#createItem}'s mandatory {@code
     * resolveEffectiveBranchId} resolution. Nullable at the JPA/DB level only as a migration safety
     * net (mandatory-ness is enforced at the controller layer, not a NOT NULL constraint - same
     * "logically mandatory, schema-nullable" convention {@code Customer#branch}/{@code
     * InventoryItem#branch} use) - {@code MenuController}'s read paths still tolerate a stray
     * branchless row defensively, but real request traffic should never produce one going forward.
     * {@link Restaurant#isMenuCentralized()} is a separate, now-vestigial Shop-level toggle (never
     * wired into any screen) left untouched by this release - it never gated visibility even before
     * this change. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "branch_id")
    private Branch branch;
}
