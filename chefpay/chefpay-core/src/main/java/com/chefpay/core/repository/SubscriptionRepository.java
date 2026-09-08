package com.chefpay.core.repository;

import com.chefpay.core.domain.Subscription;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface SubscriptionRepository extends JpaRepository<Subscription, UUID> {
    /** Bistrodesk Phase 1: replaces the old {@code findByRestaurantId} now that {@link Subscription}
     * is keyed by {@code branch_id}, not {@code restaurant_id} - see {@code Subscription.branch}'s
     * javadoc. */
    Optional<Subscription> findByBranchId(UUID branchId);
}
