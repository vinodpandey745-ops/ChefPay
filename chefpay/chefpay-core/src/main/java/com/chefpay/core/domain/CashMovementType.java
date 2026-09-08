package com.chefpay.core.domain;

/** Manual adjustment to the cash drawer that isn't a guest payment - a float top-up, a paid-out
 * for petty supplies, a bank drop, etc. See {@link CashMovement}. */
public enum CashMovementType {
    CASH_IN,
    CASH_OUT
}
