package com.chefpay.core.domain;

/**
 * Kept separate from {@link OrderStatus} because a bill can be PARTIALLY_PAID (split payment,
 * Phase 4) while the order's own lifecycle position is still just PAYMENT_PENDING - the two
 * dimensions genuinely diverge, per requirement §12 listing Status and Payment Status as
 * distinct order fields.
 */
public enum PaymentStatus {
    UNPAID,
    PARTIALLY_PAID,
    PAID,
    REFUNDED
}
