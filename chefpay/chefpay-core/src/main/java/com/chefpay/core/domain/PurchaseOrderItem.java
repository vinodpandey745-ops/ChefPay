package com.chefpay.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * One line on a {@link PurchaseOrder} (Round 12 §12-§24). {@code orderedQuantity}/{@code
 * unitPrice} are set at creation and frozen from then on (mirrors {@code OrderItem}'s
 * "unitPriceSnapshot never changes after the fact" rule) - a price change on the underlying
 * {@link InventoryItem} afterward never rewrites what was actually ordered.
 *
 * <p>{@code receivedQuantity}/{@code acceptedQuantity}/{@code damagedQuantity}/{@code
 * rejectedQuantity} capture §24's full "Receiving Adjustment" breakdown - received is what
 * physically arrived, accepted/damaged/rejected is how that arrival was triaged (accepted always
 * equals received minus damaged minus rejected, enforced in {@code
 * PurchaseOrderService#receiveItems}, not here). Only {@code acceptedQuantity} is ever posted to
 * the inventory ledger ({@code InventoryService#recordTransaction}, type {@code RECEIVE}) - damaged/
 * rejected stock was never usable, so it never inflates {@code quantityOnHand}.
 */
@Entity
@Table(name = "purchase_order_item")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
@EqualsAndHashCode(callSuper = false)
public class PurchaseOrderItem extends BaseEntity {

    /** Bistrodesk fix (production {@code StackOverflowError} class - see {@code
     * Device#lastUser}'s javadoc for the full explanation): {@link PurchaseOrder#items} is the
     * inverse side of this relationship - without this exclusion, hashing this line would walk
     * back into its parent PO's item list and re-hash every sibling line forever. */
    @EqualsAndHashCode.Exclude
    @ManyToOne(optional = false)
    @JoinColumn(name = "purchase_order_id", nullable = false)
    private PurchaseOrder purchaseOrder;

    @ManyToOne(optional = false)
    @JoinColumn(name = "inventory_item_id", nullable = false)
    private InventoryItem inventoryItem;

    @Column(nullable = false, precision = 14, scale = 3)
    private BigDecimal orderedQuantity;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal unitPrice;

    @Builder.Default
    @Column(nullable = false, precision = 14, scale = 3)
    private BigDecimal receivedQuantity = BigDecimal.ZERO;

    @Builder.Default
    @Column(nullable = false, precision = 14, scale = 3)
    private BigDecimal acceptedQuantity = BigDecimal.ZERO;

    @Builder.Default
    @Column(nullable = false, precision = 14, scale = 3)
    private BigDecimal damagedQuantity = BigDecimal.ZERO;

    @Builder.Default
    @Column(nullable = false, precision = 14, scale = 3)
    private BigDecimal rejectedQuantity = BigDecimal.ZERO;

    @Column(length = 500)
    private String receivingNotes;

    public BigDecimal lineTotal() {
        return orderedQuantity.multiply(unitPrice);
    }

    /** How much of {@link #orderedQuantity} is still outstanding - drives {@code
     * PurchaseOrderStatus#PARTIALLY_RECEIVED} vs {@code RECEIVED} at the PO level. */
    public BigDecimal remainingQuantity() {
        BigDecimal remaining = orderedQuantity.subtract(receivedQuantity);
        return remaining.compareTo(BigDecimal.ZERO) < 0 ? BigDecimal.ZERO : remaining;
    }
}
