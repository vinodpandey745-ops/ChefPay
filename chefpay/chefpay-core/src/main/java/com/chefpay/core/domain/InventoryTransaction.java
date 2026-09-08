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
 * One stock movement against an {@link InventoryItem} - the append-only ledger entry that backs
 * every change to {@code quantityOnHand}. Mirrors {@link CashMovement}'s shape: a
 * {@link BaseEntity} (so it keeps a stable id/timestamp) that is never updated after creation in
 * practice, even though nothing at the JPA layer enforces that immutability.
 *
 * <p>{@code quantity} is always stored positive; {@code type} says which direction it moved
 * ({@code RECEIVE}/{@code ADJUST} up, {@code DEDUCT}/{@code WASTE} down) - see
 * {@link InventoryTransactionType}.
 */
@Entity
@Table(name = "inventory_transaction")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
@EqualsAndHashCode(callSuper = false)
public class InventoryTransaction extends BaseEntity {

    @ManyToOne(optional = false)
    @JoinColumn(name = "item_id", nullable = false)
    private InventoryItem item;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private InventoryTransactionType type;

    @Column(nullable = false, precision = 14, scale = 3)
    private BigDecimal quantity;

    /** Stock level immediately after this transaction was applied - lets the ledger read without recomputing a running total. */
    @Column(nullable = false, precision = 14, scale = 3)
    private BigDecimal resultingQuantity;

    @Column(nullable = false, length = 500)
    private String reason;

    @ManyToOne(optional = false)
    @JoinColumn(name = "recorded_by", nullable = false)
    private AppUser recordedBy;
}
