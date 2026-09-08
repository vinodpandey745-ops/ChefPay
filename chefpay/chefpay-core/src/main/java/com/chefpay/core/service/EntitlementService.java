package com.chefpay.core.service;

import com.chefpay.core.domain.Feature;
import com.chefpay.core.domain.Subscription;
import com.chefpay.core.domain.SubscriptionStatus;
import com.chefpay.core.repository.SubscriptionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Phase 2 (items 16-28): the single source of truth for "is feature X available right now" and
 * "what is this install's subscription status" - every feature-gated screen/endpoint asks this
 * service, never re-implements the plan/date/grace-period logic itself (same established pattern
 * {@code AiService}/{@code EmailReceiptService} already follow for their own single-gated
 * features - see PHASE2_ORG_SUBSCRIPTION_DESIGN.md Section B).
 *
 * <p><b>Never trusts a client-supplied date.</b> Every computation below reads {@link
 * LocalDate#now()} - the server's own clock - and {@link Subscription#getExpiryDate()}/{@link
 * Subscription#getGracePeriodDays()} from the database. A JWT never carries subscription status
 * baked in (see {@code AuthController#login}, which deliberately does not touch subscription at
 * all) precisely so a plan change or expiry takes effect immediately, not only on next login.
 */
@Service
@RequiredArgsConstructor
public class EntitlementService {

    private final SubscriptionRepository subscriptionRepository;

    /** Bistrodesk Phase 1: renamed from {@code findForRestaurant} now that {@link Subscription} is
     * keyed by branch, not restaurant - see that entity's javadoc. Had no other caller in this
     * codebase at the time of the rename (confirmed via a full-codebase search), so this is a pure
     * rename, not a behavior change. */
    @Transactional(readOnly = true)
    public Optional<Subscription> findForBranch(UUID branchId) {
        return subscriptionRepository.findByBranchId(branchId);
    }

    /**
     * Computes this subscription's TRUE current status from the server clock, persisting it back
     * onto the row if it has drifted from whatever was last computed - so every other reader of
     * {@link Subscription#getStatus()} (a report, an export, the platform-owner screen) sees a
     * value that's actually live rather than stale from whenever a request last happened to
     * recompute it. {@link SubscriptionStatus#SUSPENDED} is a manual, platform-owner-only state
     * (item 21's "configurable expired/grace-period states" includes an operator override) and is
     * never auto-changed by this method in either direction.
     */
    @Transactional
    public SubscriptionStatus refreshStatus(Subscription subscription) {
        if (subscription.getStatus() == SubscriptionStatus.SUSPENDED) {
            return SubscriptionStatus.SUSPENDED;
        }
        LocalDate today = LocalDate.now();
        LocalDate expiry = subscription.getExpiryDate();
        SubscriptionStatus computed;
        if (!today.isAfter(expiry)) {
            computed = isWithinWarningWindow(subscription, today, expiry)
                    ? SubscriptionStatus.EXPIRING_SOON : SubscriptionStatus.ACTIVE;
        } else if (!today.isAfter(expiry.plusDays(subscription.getGracePeriodDays()))) {
            computed = SubscriptionStatus.GRACE_PERIOD;
        } else {
            computed = SubscriptionStatus.EXPIRED;
        }
        if (computed != subscription.getStatus()) {
            subscription.setStatus(computed);
            subscriptionRepository.save(subscription);
        }
        return computed;
    }

    /** Item 20's configurable warning banner thresholds, e.g. "30,15,7,3,1" - true the moment
     * {@code remainingDays} drops to or below ANY configured threshold, never before. Malformed
     * entries in the CSV (should never happen - only ever written by {@code PlatformOwnerController})
     * are skipped rather than failing the whole check. */
    private boolean isWithinWarningWindow(Subscription subscription, LocalDate today, LocalDate expiry) {
        long daysRemaining = ChronoUnit.DAYS.between(today, expiry);
        for (String raw : subscription.getWarningThresholdsDays().split(",")) {
            try {
                if (daysRemaining <= Long.parseLong(raw.trim())) {
                    return true;
                }
            } catch (NumberFormatException ignored) {
                // Skip a malformed threshold rather than failing the whole warning check.
            }
        }
        return false;
    }

    /** Days until (positive) or since (negative) {@link Subscription#getExpiryDate()} - the raw
     * number the Subscription screen's countdown/expiry display (item 19) renders directly. */
    public long remainingDays(Subscription subscription) {
        return ChronoUnit.DAYS.between(LocalDate.now(), subscription.getExpiryDate());
    }

    /** Item 24: is this ONE feature available right now. An EXPIRED or SUSPENDED subscription
     * blocks every feature regardless of what the plan includes; anything else (ACTIVE,
     * EXPIRING_SOON, GRACE_PERIOD - all still "paid up" states) is gated purely on presence in the
     * plan's {@link Feature} set, never re-checked against price/trial-ness here. */
    @Transactional
    public boolean isFeatureEnabled(Subscription subscription, String featureCode) {
        if (blocksAllFeatures(refreshStatus(subscription))) {
            return false;
        }
        return subscription.getPlan().getFeatures().stream()
                .anyMatch(f -> f.getCode().equalsIgnoreCase(featureCode));
    }

    /** Every enabled feature code at once - drives the {@code GET /api/entitlements} response the
     * client's locked/unlocked UI (item 24) reads in one call rather than one round-trip per
     * feature. */
    @Transactional
    public Set<String> enabledFeatureCodes(Subscription subscription) {
        if (blocksAllFeatures(refreshStatus(subscription))) {
            return Set.of();
        }
        return subscription.getPlan().getFeatures().stream()
                .map(Feature::getCode)
                .collect(Collectors.toSet());
    }

    private boolean blocksAllFeatures(SubscriptionStatus status) {
        return status == SubscriptionStatus.EXPIRED || status == SubscriptionStatus.SUSPENDED;
    }
}
