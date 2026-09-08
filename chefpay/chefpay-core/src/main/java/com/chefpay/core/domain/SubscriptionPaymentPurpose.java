package com.chefpay.core.domain;

/**
 * Subscription Renewal and Plan Upgrade requirement: which of the two owner-facing flows a given
 * {@link SubscriptionPayment} row was created for - "Reactivate Current Plan" (the branch's
 * existing {@link Subscription#getPlan()} is simply extended) vs "Choose Another Plan" (a
 * different, caller-selected {@link SubscriptionPlan} is swapped in on successful payment).
 */
public enum SubscriptionPaymentPurpose {
    REACTIVATE,
    PLAN_CHANGE
}
