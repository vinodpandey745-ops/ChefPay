package com.chefpay.core.domain;

/**
 * Full status set from the requirements doc. Only AVAILABLE/RESERVED/OCCUPIED/BLOCKED are
 * reachable in Phase 1 (no orders exist yet to drive the rest); ORDER_PLACED..PAYMENT_PENDING
 * are set by the order lifecycle starting Phase 2, and CLOSED loops a table back to AVAILABLE.
 * Colors/icons for each status are a client-side theme concern (ARCHITECTURE.md §7), not baked
 * in here.
 */
public enum TableStatus {
    AVAILABLE,
    RESERVED,
    OCCUPIED,
    ORDER_PLACED,
    PREPARING,
    READY,
    BILL_REQUESTED,
    PAYMENT_PENDING,
    CLOSED,
    BLOCKED
}
