package com.chefpay.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.math.BigDecimal;

/**
 * A manual cash-drawer adjustment that is not a guest payment - float top-up, paid-out for petty
 * supplies, a bank drop, etc. Phase 4's lightweight take on "Cash Management": {@code
 * BillingService.getCashSummary} reconciles {@code expected cash in drawer = cash payments + CASH_IN
 * - CASH_OUT} for a given day. A full shift/register open-close-count model (the {@code Shift}
 * entity already named in ARCHITECTURE.md §9's target entity list) is a later-phase concern; this
 * gives a usable, auditable log now without that larger workflow.
 *
 * <p>Uses {@code BaseEntity.createdAt} as its timestamp rather than a redundant field of its own.
 */
@Entity
@Table(name = "cash_movement")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
@EqualsAndHashCode(callSuper = false)
public class CashMovement extends BaseEntity {

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CashMovementType type;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false)
    private String reason;

    @ManyToOne(optional = false)
    @JoinColumn(name = "recorded_by", nullable = false)
    private AppUser recordedBy;
}
