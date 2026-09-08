package com.chefpay.core.domain;

/** Tender type for a {@link Payment} line. Kept as a fixed enum rather than a configurable table
 * for now - a restaurant's accepted payment rails change rarely enough that a code deploy is fine,
 * and it keeps {@code CASH}-specific behavior (tendered/change) type-safe in the service layer. */
public enum PaymentMethod {
    CASH,
    CARD,
    UPI,
    WALLET,
    OTHER
}
