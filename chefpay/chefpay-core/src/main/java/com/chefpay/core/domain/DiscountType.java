package com.chefpay.core.domain;

/** How a {@link Discount} preset's {@code value} is interpreted against an order's subtotal. */
public enum DiscountType {
    PERCENTAGE,
    FIXED_AMOUNT
}
