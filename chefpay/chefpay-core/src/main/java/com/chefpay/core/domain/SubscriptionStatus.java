package com.chefpay.core.domain;

/**
 * Phase 2: computed, not stored-and-trusted-forever - {@code EntitlementService} recomputes this
 * from {@link Subscription#getExpiryDate()}/{@link Subscription#getGracePeriodDays()} against the
 * server's own clock on every read, and only persists the result back onto {@link
 * Subscription#getStatus()} as a cache for display (e.g. the platform owner's own listing) - it is
 * never trusted as the source of truth for an entitlement decision, the live computation always
 * is. See items 19-21 of the request for the exact state definitions this models:
 * ACTIVE -> EXPIRING_SOON (within a configured warning threshold) -> GRACE_PERIOD (past expiry,
 * within {@code gracePeriodDays}) -> EXPIRED (past the grace period) -> SUSPENDED (platform owner
 * override, independent of dates - e.g. a support/policy hold).
 */
public enum SubscriptionStatus {
    ACTIVE,
    EXPIRING_SOON,
    GRACE_PERIOD,
    EXPIRED,
    SUSPENDED
}
