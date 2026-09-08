package com.chefpay.core.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * The single shared live order (requirement §2 - the whole point of this system). Any number of
 * terminals may fetch, add items to, and transition the same {@code Order} row; the
 * {@code @Version} column inherited from {@link BaseEntity} is what makes "two terminals edit the
 * same order at once" safe rather than a silent-overwrite race (ARCHITECTURE.md §8).
 *
 * <p>Tax/discount/service-charge/tip are real columns from Phase 2 on (so the shape matches
 * requirement §12 end to end) but are only ever zero/subtotal-passthrough until Phase 4 wires up
 * real tax/discount computation - see {@code OrderService} in chefpay-server for where that math
 * will live (always server-side, never trusted from a client per §50).
 */
@Entity
@Table(name = "customer_order")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
@EqualsAndHashCode(callSuper = false)
public class Order extends BaseEntity {

    @Column(nullable = false, unique = true)
    private String orderNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderType orderType;

    /** Null for TAKEAWAY/DELIVERY/PHONE_ORDER/ONLINE_ORDER; required for DINE_IN/QUICK_SERVICE at a table. */
    @ManyToOne
    @JoinColumn(name = "table_id")
    private RestaurantTable table;

    /** Bistrodesk Phase 2: the real fix for a gap {@code OrderRepository#findByStatusNotInAndBranch}
     * has always documented in its own javadoc - {@code Order} had NO branch column of its own, so a
     * non-table order (TAKEAWAY/DELIVERY/PHONE_ORDER/ONLINE_ORDER) could never actually be attributed
     * to a branch at all, and "every non-table order is deliberately included regardless of the
     * branch filter" was a documented workaround, not a design choice. Populated by {@code
     * OrderService#openOrCreateOrder} on every NEW order from now on - derived from {@link #table}'s
     * own branch when one is given (the table is authoritative; a client-supplied branch that
     * disagrees with the table's real branch is rejected, never silently overridden), or supplied
     * directly for a non-table order. Nullable so a pre-existing order (created before this column
     * existed) keeps working exactly as before via {@link #getEffectiveBranch()}'s fallback. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "branch_id")
    private Branch branch;

    /** Lightweight inline customer capture - kept unchanged (Phase 5) so nothing that already reads
     * these two columns (receipts, order-history lists, etc.) has to change. */
    private String customerName;
    private String customerPhone;

    /** Bistrodesk Phase 1: the real FK {@link Customer}'s own javadoc named as the natural next
     * step once one existed ("needs a real Order.customerId FK to do reliably... left for that
     * follow-up rather than built on a shaky match-by-string today"). Nullable - an order with no
     * matched/created Customer record still works exactly as before, via {@link #customerName}/
     * {@link #customerPhone} alone; this is populated going forward by the quick-create/search flow
     * (requirement #2, Phase 7 of this effort). Not branch-scoped itself - {@link Customer} stays
     * one shared record per phone number across a restaurant's branches (see that entity's own
     * design intent); which branch an order happened at is answered by the order/table itself, not
     * by scoping the customer record. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id")
    private Customer customer;

    @ManyToOne
    @JoinColumn(name = "waiter_id")
    private AppUser waiter;

    @ManyToOne
    @JoinColumn(name = "cashier_id")
    private AppUser cashier;

    /** Assigned delivery rider (Round 9) - null when unassigned or when {@code
     * Restaurant#deliveryBoyFeatureEnabled} is off. Most relevant for DELIVERY/ONLINE_ORDER orders
     * but not restricted to those types at the data layer, same as {@link #table} isn't restricted
     * to DINE_IN - the client screens are what actually gate which order types show the picker. */
    @ManyToOne
    @JoinColumn(name = "delivery_boy_id")
    private DeliveryBoy deliveryBoy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private OrderStatus status = OrderStatus.DRAFT;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private PaymentStatus paymentStatus = PaymentStatus.UNPAID;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private KitchenPriority priority = KitchenPriority.NORMAL;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("createdAt asc")
    @Builder.Default
    private List<OrderItem> items = new ArrayList<>();

    @Column(nullable = false, precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal subtotal = BigDecimal.ZERO;

    @Column(nullable = false, precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal discountAmount = BigDecimal.ZERO;

    /** Free-text note on why a discount was applied (e.g. preset name, or a manual reason) - Phase 4. */
    private String discountReason;

    @Column(nullable = false, precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal taxAmount = BigDecimal.ZERO;

    @Column(nullable = false, precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal serviceChargeAmount = BigDecimal.ZERO;

    @Column(nullable = false, precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal tipAmount = BigDecimal.ZERO;

    @Column(nullable = false, precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal totalAmount = BigDecimal.ZERO;

    private String notes;

    private LocalDateTime sentToKitchenAt;
    private LocalDateTime billedAt;
    private LocalDateTime paidAt;
    private LocalDateTime closedAt;

    public void addItem(OrderItem item) {
        item.setOrder(this);
        items.add(item);
    }

    /** Bistrodesk Phase 2: the branch this order actually belongs to - prefers the direct {@link
     * #branch} column (populated for every order created from this phase on, dine-in or not), and
     * falls back to the historical {@code table.floor.branch} chain for any order created before
     * that column existed (a dine-in order only - a pre-existing non-table order genuinely has no
     * recorded branch and returns null here, exactly as it always implicitly did; this is not a
     * regression, just not retroactively fixable without a human deciding which branch each old
     * order belonged to). Every branch-scoping check/query added in this phase goes through this
     * one method rather than re-deriving the fallback chain ad hoc. */
    public Branch getEffectiveBranch() {
        if (branch != null) {
            return branch;
        }
        return table == null ? null : table.getFloor().getBranch();
    }

    /**
     * Recomputes subtotal/total from current line items. Called by the service layer after any
     * item mutation, inside the same transaction, so the stored total never drifts from its
     * items - the server is the only source of truth for money (§50), never the client.
     *
     * <p>This method itself only ever passes {@code taxAmount}/{@code discountAmount}/
     * {@code serviceChargeAmount} straight through unchanged - it has no access to tax config to
     * compute a fresh {@code taxAmount} with (that lives in {@code TaxCalculationService}, a Spring
     * bean this plain JPA entity can't reach). Bistrodesk Phase 6 (bug #12, "tax not live in cart"):
     * every item-mutating method in {@code OrderService} (add/update/cancel/status-change) now
     * calls its own {@code applyLiveTax(Order)} helper immediately around this method instead of
     * calling it bare, which is what actually keeps {@code taxAmount} live pre-checkout; before that
     * fix this field simply stayed at zero until {@code BillingService#generateBill} froze it.
     * {@code discountAmount}/{@code serviceChargeAmount} remain billing-phase-only fields (only
     * ever set by {@code BillingService}), unaffected by that fix.
     */
    public void recalculateTotals() {
        BigDecimal newSubtotal = items.stream()
                .filter(i -> i.getStatus() != OrderItemStatus.CANCELLED && i.getStatus() != OrderItemStatus.VOIDED)
                .map(OrderItem::lineTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        this.subtotal = newSubtotal;
        this.totalAmount = newSubtotal
                .subtract(discountAmount)
                .add(taxAmount)
                .add(serviceChargeAmount)
                .add(tipAmount);
    }
}
