package com.chefpay.server.subscription;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Read-only DTOs for the restaurant's own Subscription screen (item 25) - nothing here is
 * writable through this module; every mutation goes through {@code PlatformOwnerController}
 * instead (see PHASE2_ORG_SUBSCRIPTION_DESIGN.md Section F). */
public class SubscriptionDtos {

    private SubscriptionDtos() {
    }

    /** Bistrodesk Phase 4 (requirement #24's Admin UI): {@code branchId}/{@code branchName} let a
     * caller that lists MULTIPLE subscriptions at once (Bistrodesk Admin's branch list, {@code
     * PlatformOwnerController#listSubscriptions}) tell them apart - the restaurant's own single-
     * branch Subscription screen ({@code SubscriptionController}) already knows which branch it
     * asked about, but populates these too now for shape consistency (one DTO, two readers). */
    public record SubscriptionDto(
            UUID id,
            UUID branchId,
            String branchName,
            String planName,
            UUID planId,
            String status,
            LocalDate startDate,
            LocalDate expiryDate,
            long remainingDays,
            int gracePeriodDays,
            String warningThresholdsDays,
            String supportPhone,
            long version
    ) {
    }

    public record PlanDto(
            UUID id,
            String name,
            String description,
            int durationDays,
            BigDecimal price,
            BigDecimal gstPercent,
            Integer maxBranches,
            Integer maxTerminals,
            Integer maxUsers,
            boolean trial,
            boolean active,
            int displayOrder,
            List<String> featureCodes
    ) {
    }

    public record EntitlementsResponse(List<String> enabledFeatures) {
    }
}
