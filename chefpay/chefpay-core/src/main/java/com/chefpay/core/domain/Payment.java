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
 * One tender against an {@link Order}'s bill - Phase 4 (requirement's "Payments"/"Receipt"). An
 * order can carry more than one {@code Payment} row (split bill: part cash, part card, or several
 * guests each paying their own share) - {@code BillingService} sums the non-voided rows to get
 * amount paid / balance due rather than the order holding a single "amountPaid" column that would
 * have to be kept in lock-step by hand.
 *
 * <p>{@code tenderedAmount}/{@code changeAmount} are only meaningful for {@link PaymentMethod#CASH}
 * (what the guest physically handed over vs. what's owed back); every other method has {@code
 * amount} be the exact charge with both of those left null. Voiding (correcting a mis-entered
 * payment) is a soft-delete via {@code voided}/{@code voidReason} rather than a hard delete, so the
 * audit trail and the original receipt number are never lost - see {@code BillingService.voidPayment}.
 */
@Entity
@Table(name = "payment")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
@EqualsAndHashCode(callSuper = false)
public class Payment extends BaseEntity {

    @ManyToOne(optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentMethod method;

    /** Amount actually applied toward the bill (never more than the balance due at the time). */
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    /** CASH only: what the guest physically handed over. Null for every other method. */
    @Column(precision = 12, scale = 2)
    private BigDecimal tenderedAmount;

    /** CASH only: {@code tenderedAmount - amount}. Null for every other method. */
    @Column(precision = 12, scale = 2)
    private BigDecimal changeAmount;

    /** Card/UPI/wallet transaction reference from the payment terminal or gateway, if any. */
    private String referenceNumber;

    @Column(nullable = false, unique = true)
    private String receiptNumber;

    @ManyToOne(optional = false)
    @JoinColumn(name = "received_by", nullable = false)
    private AppUser receivedBy;

    @Column(nullable = false)
    private LocalDateTime receivedAt;

    @Builder.Default
    private boolean voided = false;

    private String voidReason;
}
