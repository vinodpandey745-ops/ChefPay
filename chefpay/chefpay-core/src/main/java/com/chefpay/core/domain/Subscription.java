package com.chefpay.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Phase 2: originally exactly one row per install (the {@code uq_subscription_restaurant} unique
 * constraint - see V29's migration comment for why this was one-per-install rather than one-per-
 * "organization" in a shared multi-tenant sense, per the confirmed hosting model).
 *
 * <p><b>Bistrodesk Phase 1 (deliberate, user-confirmed deviation from the recommended default):
 * subscription is now per-BRANCH, not per-restaurant.</b> {@link #branch} is the new primary
 * association (see V31's migration for {@code branch_id}/{@code uq_subscription_branch}); every
 * entitlement/plan lookup goes through it from now on. {@link #restaurant} is kept mapped, but
 * {@code @Deprecated} and never read by any business logic any more - purely so the pre-existing
 * {@code restaurant_id NOT NULL} column (V29) keeps being satisfied on every insert without a
 * destructive schema change in this same release (see that field's own javadoc for exactly how
 * it's populated). Every existing single-branch install backfills one {@code Subscription} row per
 * {@link Branch} on next boot (see {@code DataSeeder#ensurePhase2Backfill}), so an install that
 * only ever had one branch sees no behavior change at all; a real multi-branch install can now
 * assign a different plan per branch, matching the confirmed requirement that a "shop" grows by
 * adding branches, each separately licensed. Writable only through {@code PlatformOwnerController}
 * (now branch-scoped there too); the restaurant's own Manager UI can only read it (via {@code
 * SubscriptionController}, gated on {@code SUBSCRIPTION_VIEW}).
 */
@Entity
@Table(name = "subscription")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
@EqualsAndHashCode(callSuper = false)
public class Subscription extends BaseEntity {

    /** Round 27 (pre-deployment audit) fix, carried over unchanged by the Bistrodesk Phase 1
     * addition above: {@code @ManyToOne} defaults to EAGER when no fetch is given - this was missed
     * when {@link Branch#getRestaurant()} got the identical fix for the production OOM crash (see
     * that field's javadoc for the full chain). {@link Branch} itself eagerly drags in its own
     * {@link Restaurant} unless loaded lazily (see {@code Branch#restaurant}'s own fix), and this
     * association sits on the hot, frequently-polled {@code GET /api/entitlements} path ({@code
     * SubscriptionController}). LAZY is safe: every real caller runs inside an HTTP request thread
     * under this app's default {@code open-in-view=true}.
     *
     * <p>Nullable at the JPA/DB level (unlike {@link #restaurant}, which stays {@code nullable =
     * false} for legacy-column reasons - see that field's javadoc) for exactly the same reason
     * {@link Branch#getBranchCode()} is nullable: a column that must be backfilled on next boot for
     * every already-existing row cannot also be declared NOT NULL up front without risking a failed
     * schema migration on whichever database already has rows. {@code DataSeeder
     * #ensurePhase2Backfill} guarantees every {@link Branch} has exactly one {@link Subscription}
     * within one boot cycle of this change shipping; any NEW subscription created afterward (via
     * {@code PlatformOwnerController#upsertSubscription}) always supplies a branch, so in practice
     * this is null only for the brief window before that backfill runs. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "branch_id", unique = true)
    private Branch branch;

    /** @deprecated Bistrodesk Phase 1: superseded by {@link #branch} - kept mapped (not removed)
     * purely so the pre-existing {@code restaurant_id NOT NULL} database column (V29) keeps being
     * satisfied on every insert without a destructive/dialect-specific schema migration in this
     * same release (see {@link Subscription}'s own class javadoc, and V31's migration comment, for
     * why dropping that column now was judged higher-risk than keeping it as an unread legacy
     * value). Every call site that builds a new {@code Subscription} sets this to {@code
     * branch.getRestaurant()} - see {@code DataSeeder#ensurePhase2Backfill} and {@code
     * PlatformOwnerController#upsertSubscription}. Never read by any business logic - {@link
     * #branch} is the only association anything should ever query through now. A future migration,
     * once this change has run cleanly in production for a while, can drop both this field and the
     * underlying column together. */
    @Deprecated
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "restaurant_id", nullable = false)
    private Restaurant restaurant;

    @ManyToOne(optional = false)
    @JoinColumn(name = "subscription_plan_id", nullable = false)
    private SubscriptionPlan plan;

    /** A display/cache of {@code EntitlementService}'s last computed status - see
     * {@link SubscriptionStatus}'s javadoc for why this is never trusted as the live source of
     * truth for an actual entitlement decision. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SubscriptionStatus status;

    /** Per item 18 of the request: configurable per install, but the rule actually implemented
     * here is "subscription starts when the {@link Subscription} row itself is created" - i.e. at
     * organization/first-activation time, set once by {@code PlatformOwnerController} and never
     * silently reinterpreted, so there is exactly one clear, documented rule rather than several
     * competing ones. */
    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "expiry_date", nullable = false)
    private LocalDate expiryDate;

    /** How many days past {@link #expiryDate} the install keeps working (in a reduced/GRACE_PERIOD
     * state) before actually becoming EXPIRED - item 21's configurable grace period. Zero is a
     * valid, explicit choice (no grace at all), so this is an {@code int}, not a feature flag. */
    @Builder.Default
    @Column(name = "grace_period_days", nullable = false)
    private int gracePeriodDays = 3;

    /** CSV of days-before-expiry to show the soft warning banner (item 20), e.g. "30,15,7,3,1" -
     * configurable per install rather than a single hardcoded threshold, since a busy multi-branch
     * client and a single small restaurant may want very different lead time. Parsed by {@code
     * EntitlementService}, never re-parsed ad hoc elsewhere. */
    @Builder.Default
    @Column(name = "warning_thresholds_days", nullable = false, length = 100)
    private String warningThresholdsDays = "30,15,7,3,1";

    /** Item 27's "Contact Support" number - configurable per install (a platform owner running
     * several regions/brands may hand out a different number per client) rather than hardcoded
     * into the frontend. Null = the renewal screen simply omits the "call for help" line. */
    @Column(name = "support_phone", length = 32)
    private String supportPhone;

    /** Item 29's offline-grace bookkeeping: the last time a client successfully fetched this
     * subscription's live status while online, used by the client to bound how long a cached
     * value stays trusted while offline - see {@code EntitlementService}'s javadoc. Not touched by
     * anything except the read path that serves {@code GET /api/subscription}. */
    @Column(name = "last_offline_validated_at")
    private LocalDateTime lastOfflineValidatedAt;
}
