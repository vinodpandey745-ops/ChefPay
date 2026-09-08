package com.chefpay.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
import java.time.LocalDateTime;

/**
 * One line of a shared {@link Order}, with independent kitchen-facing status per requirement
 * §14/§48. Timestamps mirror the "Created, Sent, Accepted, Started, Ready, Served" list in §48
 * so prep-time/queue-time/delay reporting (Phase 5) has real data from day one instead of a
 * retrofit.
 */
@Entity
@Table(name = "order_item")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
@EqualsAndHashCode(callSuper = false)
public class OrderItem extends BaseEntity {

    /** Bistrodesk fix (production {@code StackOverflowError} class - see {@code
     * Device#lastUser}'s javadoc for the full explanation and the confirmed live crash this same
     * shape caused elsewhere): {@link Order#items} is the inverse side of this relationship, and
     * without this exclusion hashing an {@code OrderItem} would walk back into its parent {@code
     * Order}'s item list, re-hashing every sibling {@code OrderItem} (this one included) forever. */
    @EqualsAndHashCode.Exclude
    @ManyToOne(optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @ManyToOne(optional = false)
    @JoinColumn(name = "menu_item_id", nullable = false)
    private MenuItem menuItem;

    /** BigDecimal to support both discrete counts and loose/weighed items (e.g. 1.5 kg). */
    @Column(nullable = false, precision = 10, scale = 3)
    private BigDecimal quantity;

    /** Price snapshot at add-time so later menu price changes never retroactively alter an open order. */
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal unitPriceSnapshot;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private OrderItemStatus status = OrderItemStatus.ADDED;

    @Builder.Default
    private boolean priority = false;

    private String specialInstructions;

    /** Free-text for now (e.g. "Extra Spicy, No Onion") - structured modifiers land with menu modifier groups in a later pass. */
    private String modifiersSummary;

    private String cancelReason;

    private LocalDateTime sentAt;
    private LocalDateTime acceptedAt;
    private LocalDateTime startedAt;
    private LocalDateTime readyAt;
    private LocalDateTime servedAt;

    /** KOT (Kitchen Order Ticket) number assigned to this line when it's sent to the kitchen -
     * populated by {@code OrderService#sendToKitchen} via a {@link NumberSequence} series called
     * "KOT". Every item sent to the kitchen together in one "send to kitchen" action shares the
     * same value. Null until the item has been sent at least once. */
    private Long kotNumber;

    public BigDecimal lineTotal() {
        return unitPriceSnapshot.multiply(quantity);
    }
}
