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

@Entity
@Table(name = "restaurant_table")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
@EqualsAndHashCode(callSuper = false)
public class RestaurantTable extends BaseEntity {

    @ManyToOne(optional = false)
    @JoinColumn(name = "floor_id", nullable = false)
    private Floor floor;

    /** Display name/number, e.g. "T1", "Patio 4". */
    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private int seatingCapacity;

    /** Free-text sub-grouping within a floor, e.g. "Indoor", "Patio", "Bar". */
    private String section;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private TableStatus status = TableStatus.AVAILABLE;

    /** Grid position for the visual table matrix. */
    private Integer gridRow;
    private Integer gridColumn;

    @Builder.Default
    private boolean active = true;

    /**
     * Whether this table may move from its current status to {@code next}. Mirrors the
     * order-driven lifecycle: a table becomes OCCUPIED/ORDER_PLACED when an order starts on it,
     * flows through the kitchen/billing states in step with that order, and returns to AVAILABLE
     * once the guest has paid in full. Reservation and manual blocking are the only status changes
     * not driven by an order.
     *
     * <p>Occupied-ish statuses may go straight to AVAILABLE - a fully paid order frees the table
     * immediately ({@code BillingService#recordPayment} does exactly this via
     * {@code syncTableStatus} the moment the balance hits zero); this codebase's order lifecycle
     * never actually visits ORDER status CLOSED afterward; PAID is already the terminal "guest is
     * done" state as far as the table is concerned. An earlier version of this method required
     * CLOSED/BLOCKED specifically to reach AVAILABLE, which meant a table a guest had just fully
     * paid at could never turn green again - going straight to RESERVED is still blocked, though,
     * since a table should pass back through AVAILABLE before anyone can reserve it.
     */
    public boolean canTransitionTo(TableStatus next) {
        if (this.status == next) {
            return true;
        }
        return switch (this.status) {
            case AVAILABLE -> next == TableStatus.RESERVED || next == TableStatus.OCCUPIED
                    || next == TableStatus.ORDER_PLACED || next == TableStatus.BLOCKED;
            case RESERVED -> next == TableStatus.OCCUPIED || next == TableStatus.AVAILABLE
                    || next == TableStatus.BLOCKED;
            case OCCUPIED, ORDER_PLACED, PREPARING, READY, BILL_REQUESTED, PAYMENT_PENDING -> next != TableStatus.RESERVED;
            case CLOSED, BLOCKED -> next == TableStatus.AVAILABLE;
        };
    }
}
