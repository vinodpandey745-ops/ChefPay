package com.chefpay.core.domain;

/**
 * Phase 2: the Organization's (=Restaurant's) own operational status, distinct from
 * {@link Subscription.SubscriptionStatus} - an org can be ACTIVE with an EXPIRED subscription
 * (the restaurant still exists, it just can't use gated features until it renews) just as easily
 * as it can be SUSPENDED/CLOSED with a perfectly current subscription (the platform owner shut it
 * down for an unrelated reason - e.g. a support/policy issue). Always persisted by name
 * ({@code @Enumerated(EnumType.STRING)}), never ordinal.
 */
public enum RestaurantStatus {
    ACTIVE,
    SUSPENDED,
    CLOSED
}
