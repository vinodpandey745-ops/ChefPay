package com.chefpay.core.domain;

/**
 * What kind of movement an {@link InventoryTransaction} represents against an
 * {@link InventoryItem}'s stock. {@code RECEIVE} and {@code ADJUST} (upward corrections) increase
 * {@code quantityOnHand}; {@code DEDUCT} and {@code WASTE} decrease it. Kept as two "reasons" per
 * direction (rather than a signed-quantity-only model) so the transaction log stays readable for
 * reconciliation - "why did this drop" is answered by the type, not just the sign.
 */
public enum InventoryTransactionType {
    RECEIVE,
    ADJUST,
    DEDUCT,
    WASTE
}
