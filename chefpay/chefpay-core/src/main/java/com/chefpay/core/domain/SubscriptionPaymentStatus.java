package com.chefpay.core.domain;

/**
 * Lifecycle of one {@link SubscriptionPayment} row. {@code CREATED} is the only state in which a
 * payment may still be verified/activated ({@code SubscriptionController#verifyRenewal}) - once a
 * row leaves {@code CREATED} (to {@code SUCCESS}, {@code FAILED}, or {@code CANCELLED}) it is
 * final and is never re-activated, so a Razorpay order id can never be replayed against the
 * subscription twice. See {@link SubscriptionPayment}'s own javadoc for the guarantee this
 * protects: the {@link Subscription} itself changes only on the transition into {@code SUCCESS}.
 */
public enum SubscriptionPaymentStatus {
    CREATED,
    SUCCESS,
    FAILED,
    CANCELLED
}
