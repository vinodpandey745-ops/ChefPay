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
import java.time.LocalDateTime;

/**
 * A standalone guest directory (Phase 5c). Originally deliberately NOT foreign-keyed from
 * {@link Order} - {@code Order.customerName}/{@code customerPhone} were the lightweight inline
 * capture every order screen already wrote, so adding this directory didn't require touching the
 * order-creation flow or a schema migration on `customer_order`.
 *
 * <p>Bistrodesk Phase 1/7 (requirement #2) added that real FK after all: {@code Order.customer}
 * (nullable - see its own javadoc), populated by {@code OrderService#updateCustomerDetails} and
 * {@code PosTerminalPage}'s customer search/quick-create flow. {@link #visitCount}/
 * {@link #totalSpend} are NOT yet auto-incremented off completed payments even now that a reliable
 * FK exists to key that off of - still a real follow-up, just no longer blocked on "needs a real FK
 * to do reliably" the way this javadoc used to say; the FK now exists, the auto-increment itself is
 * simply still unbuilt.
 *
 * <p>{@link #phone} is how staff look this record up when quick-adding a guest to a Delivery/
 * Pickup order (see {@code TableMatrixView}'s Delivery/Pickup buttons) - intentionally not unique
 * at the database level (a shared family/office number is common), just the primary search key.
 */
@Entity
@Table(name = "customer")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
@EqualsAndHashCode(callSuper = false)
public class Customer extends BaseEntity {

    @Column(nullable = false)
    private String name;

    private String phone;

    private String email;

    @Column(length = 1000)
    private String notes;

    @Builder.Default
    @Column(nullable = false)
    private int visitCount = 0;

    @Builder.Default
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal totalSpend = BigDecimal.ZERO;

    private LocalDateTime lastVisitAt;

    /** Bistrodesk branch-isolation release (requirement #6): reverses an earlier explicit design
     * choice (this entity used to be documented as deliberately "one shared record per phone number
     * across a restaurant's branches") - the user has since asked for the opposite: a customer
     * belongs to exactly one branch going forward. Nullable at the JPA/DB level only during
     * migration (same "logically mandatory, schema-nullable" convention as {@link
     * InventoryItem#getBranch()}) - every create path resolves and sets one (see
     * {@code CustomerController#create}), and every pre-existing branchless row is backfilled onto
     * the install's first branch at startup (see {@code DataSeeder}'s branch-backfill step) rather
     * than left null the way a genuinely optional, shared item is. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "branch_id")
    private Branch branch;
}
