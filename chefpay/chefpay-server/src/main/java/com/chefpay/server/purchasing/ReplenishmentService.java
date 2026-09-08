package com.chefpay.server.purchasing;

import com.chefpay.core.domain.AppUser;
import com.chefpay.core.domain.Branch;
import com.chefpay.core.domain.InventoryItem;
import com.chefpay.core.domain.InventoryTransaction;
import com.chefpay.core.domain.InventoryTransactionType;
import com.chefpay.core.domain.PurchaseOrder;
import com.chefpay.core.domain.PurchaseOrderItem;
import com.chefpay.core.domain.PurchaseOrderStatus;
import com.chefpay.core.domain.Restaurant;
import com.chefpay.core.repository.AppUserRepository;
import com.chefpay.core.repository.BranchRepository;
import com.chefpay.core.repository.InventoryItemRepository;
import com.chefpay.core.repository.InventoryTransactionRepository;
import com.chefpay.core.repository.PurchaseOrderRepository;
import com.chefpay.core.repository.RestaurantRepository;
import com.chefpay.server.ai.AiService;
import com.chefpay.server.common.ApiException;
import com.chefpay.server.notifications.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Round 12 §21 - the "AI/rule-based inventory replenishment suggestion engine." Per the user's
 * own steer for this round ("rules plus an AI-written summary"), {@link #generateSuggestions} is
 * ALWAYS a deterministic rules-only computation (works with zero external dependencies, exactly
 * the requirement's "designed to work even without external AI"); the AI narrative on top is a
 * pure bonus that degrades to {@code null} the moment AI isn't configured or the call fails - it
 * never blocks or changes a single suggested quantity. Nothing here ever creates or sends a PO
 * automatically - this only ever hands a manager a list to review (§21's explicit "never
 * auto-creating/sending a PO without authorization").
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ReplenishmentService {

    /** Fixed assumption for the demand-based formula below - see {@link #generateSuggestions}'s
     * javadoc for exactly how it's used. A per-item configurable lead time is real future scope;
     * this deliberately documented constant keeps the formula honest about that simplification
     * rather than fabricating per-item precision this schema doesn't yet carry. */
    private static final int ASSUMED_LEAD_TIME_DAYS = 7;
    private static final int CONSUMPTION_HISTORY_DAYS = 14;

    /** Round 14 (F3.1): lookback window for the day-of-week seasonality model - 8 weeks gives at
     * least 8 observations of any given weekday, enough for a weekday-specific average to mean
     * something without reaching back so far that a since-changed menu/season skews it. */
    private static final int SEASONAL_HISTORY_DAYS = 56;

    private final InventoryItemRepository inventoryItemRepository;
    private final InventoryTransactionRepository transactionRepository;
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final AiService aiService;
    private final PurchaseOrderService purchaseOrderService;
    private final BranchRepository branchRepository;
    private final AppUserRepository appUserRepository;
    private final RestaurantRepository restaurantRepository;
    private final NotificationService notificationService;

    /**
     * Deterministic suggested-quantity formula, per item at/below its reorder threshold (mirrors
     * {@code InventoryService#isLowStock}'s own definition of "low"): compute a demand-based
     * target level from recent daily consumption pace (average {@code DEDUCT}/{@code WASTE}
     * ledger movement over the last {@value #CONSUMPTION_HISTORY_DAYS} days × {@value
     * #ASSUMED_LEAD_TIME_DAYS}-day lead time, plus the reorder threshold itself as a safety
     * buffer) when there's enough history to compute a pace; falls back to a simple "reorder up
     * to double the threshold" heuristic for a new/rarely-moving item with no usable history.
     * Either way, the final suggested quantity subtracts what's already on hand AND whatever is
     * still outstanding on this item across every open PO (§21's "pending POs"), so this never
     * suggests reordering stock that's already on its way.
     */
    @Transactional(readOnly = true)
    public PurchaseOrderDtos.ReplenishmentSuggestionsResponse generateSuggestions() {
        List<InventoryItem> lowStock = inventoryItemRepository.findByActiveTrueOrderByNameAsc().stream()
                .filter(i -> i.getReorderThreshold() != null && i.getQuantityOnHand().compareTo(i.getReorderThreshold()) <= 0)
                .toList();

        List<PurchaseOrderDtos.ReplenishmentSuggestionDto> suggestions = new ArrayList<>();
        List<PurchaseOrderStatus> openStatuses = List.of(PurchaseOrderStatus.DRAFT, PurchaseOrderStatus.PENDING_APPROVAL,
                PurchaseOrderStatus.APPROVED, PurchaseOrderStatus.SENT_TO_SUPPLIER, PurchaseOrderStatus.PARTIALLY_RECEIVED);

        for (InventoryItem item : lowStock) {
            BigDecimal pendingOrdered = purchaseOrderRepository.findOpenItemsForInventoryItem(item.getId(), openStatuses).stream()
                    .map(PurchaseOrderItem::remainingQuantity)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            LocalDateTime since = LocalDateTime.now().minusDays(CONSUMPTION_HISTORY_DAYS);
            List<InventoryTransaction> recent = transactionRepository.findByItemIdOrderByCreatedAtDesc(item.getId()).stream()
                    .filter(t -> t.getCreatedAt() != null && t.getCreatedAt().isAfter(since))
                    .filter(t -> t.getType() == InventoryTransactionType.DEDUCT || t.getType() == InventoryTransactionType.WASTE)
                    .toList();
            BigDecimal totalConsumed = recent.stream().map(InventoryTransaction::getQuantity).reduce(BigDecimal.ZERO, BigDecimal::add);

            BigDecimal targetLevel;
            String reason;
            if (totalConsumed.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal avgDaily = totalConsumed.divide(BigDecimal.valueOf(CONSUMPTION_HISTORY_DAYS), 3, RoundingMode.HALF_UP);
                targetLevel = avgDaily.multiply(BigDecimal.valueOf(ASSUMED_LEAD_TIME_DAYS)).add(item.getReorderThreshold());
                reason = "Averaging " + avgDaily.stripTrailingZeros().toPlainString() + " " + item.getUnit() + "/day over the last "
                        + CONSUMPTION_HISTORY_DAYS + " days; targets " + ASSUMED_LEAD_TIME_DAYS + " days of cover plus the reorder threshold.";
            } else {
                targetLevel = item.getReorderThreshold().multiply(BigDecimal.valueOf(2));
                reason = "No recent consumption history - targeting double the reorder threshold as a safe starting point.";
            }

            BigDecimal suggested = targetLevel.subtract(item.getQuantityOnHand()).subtract(pendingOrdered);
            if (suggested.compareTo(BigDecimal.ZERO) <= 0) {
                continue; // already covered by on-hand stock + what's already on order - nothing to suggest
            }
            suggestions.add(new PurchaseOrderDtos.ReplenishmentSuggestionDto(item.getId(), item.getName(), item.getUnit(),
                    item.getQuantityOnHand(), item.getReorderThreshold(), pendingOrdered,
                    suggested.setScale(3, RoundingMode.HALF_UP), reason));
        }

        String aiNarrative = tryGenerateAiNarrative(suggestions);
        return new PurchaseOrderDtos.ReplenishmentSuggestionsResponse(suggestions, aiNarrative);
    }

    /** Round 12's "rules plus an AI-written summary" - purely additive. Any failure (AI not
     * configured, provider error) simply leaves the narrative {@code null}; the rules-only
     * suggestions above are already complete and useful without it. */
    private String tryGenerateAiNarrative(List<PurchaseOrderDtos.ReplenishmentSuggestionDto> suggestions) {
        if (suggestions.isEmpty()) {
            return null;
        }
        try {
            Restaurant restaurant = aiService.loadRestaurant();
            aiService.assertEnabled(restaurant, restaurant.isAiReplenishmentNotesEnabled(), "AI replenishment notes");
            StringBuilder items = new StringBuilder();
            for (PurchaseOrderDtos.ReplenishmentSuggestionDto s : suggestions) {
                items.append("- ").append(s.itemName()).append(": ").append(s.quantityOnHand().stripTrailingZeros().toPlainString())
                        .append(" ").append(s.unit()).append(" on hand, suggest ordering ")
                        .append(s.suggestedQuantity().stripTrailingZeros().toPlainString()).append(" ").append(s.unit())
                        .append(" (").append(s.reason()).append(")\n");
            }
            String systemPrompt = "You help a restaurant manager review a rule-generated inventory replenishment list. "
                    + "You will be given each low-stock item with its current quantity, a suggested reorder quantity, and "
                    + "why. Write a short plain-text summary (a few sentences, no markdown headers/bullets) highlighting "
                    + "anything that looks urgent or unusual, and confirming the list looks reasonable overall.";
            return aiService.chatText(restaurant, systemPrompt, items.toString());
        } catch (ApiException notConfiguredOrFailed) {
            return null;
        }
    }

    // ---- Round 14 (F3.1): day-of-week seasonality-aware forecasting ----

    /**
     * Same low-stock item selection and "subtract on-hand + pending-ordered" logic as {@link
     * #generateSuggestions}, but replaces the flat {@value #CONSUMPTION_HISTORY_DAYS}-day daily
     * average with a per-weekday demand projection: for each of the next {@value
     * #ASSUMED_LEAD_TIME_DAYS} calendar days, it looks up that specific day-of-week's average
     * historical consumption (over the last {@value #SEASONAL_HISTORY_DAYS} days) and sums those
     * {@value #ASSUMED_LEAD_TIME_DAYS} day-specific averages - so a lead time spanning, say, a
     * weekend gets a higher projection for a restaurant that's consistently busier on weekends,
     * instead of one flat number blind to which days of the week are actually included. Falls back
     * to the overall (non-weekday-specific) average for any day whose specific weekday has no
     * observations yet, and all the way down to {@link #generateSuggestions}'s own "double the
     * threshold" heuristic when there's no usable history at all - never a divide-by-zero or a
     * silently-wrong zero projection.
     */
    @Transactional(readOnly = true)
    public PurchaseOrderDtos.ReplenishmentSuggestionsResponse generateSeasonalSuggestions() {
        List<InventoryItem> lowStock = inventoryItemRepository.findByActiveTrueOrderByNameAsc().stream()
                .filter(i -> i.getReorderThreshold() != null && i.getQuantityOnHand().compareTo(i.getReorderThreshold()) <= 0)
                .toList();

        List<PurchaseOrderDtos.ReplenishmentSuggestionDto> suggestions = new ArrayList<>();
        List<PurchaseOrderStatus> openStatuses = List.of(PurchaseOrderStatus.DRAFT, PurchaseOrderStatus.PENDING_APPROVAL,
                PurchaseOrderStatus.APPROVED, PurchaseOrderStatus.SENT_TO_SUPPLIER, PurchaseOrderStatus.PARTIALLY_RECEIVED);
        LocalDateTime seasonalSince = LocalDateTime.now().minusDays(SEASONAL_HISTORY_DAYS);

        for (InventoryItem item : lowStock) {
            BigDecimal pendingOrdered = purchaseOrderRepository.findOpenItemsForInventoryItem(item.getId(), openStatuses).stream()
                    .map(PurchaseOrderItem::remainingQuantity)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            List<InventoryTransaction> history = transactionRepository.findByItemIdOrderByCreatedAtDesc(item.getId()).stream()
                    .filter(t -> t.getCreatedAt() != null && t.getCreatedAt().isAfter(seasonalSince))
                    .filter(t -> t.getType() == InventoryTransactionType.DEDUCT || t.getType() == InventoryTransactionType.WASTE)
                    .toList();

            if (history.isEmpty()) {
                BigDecimal targetLevel = item.getReorderThreshold().multiply(BigDecimal.valueOf(2));
                addSuggestionIfPositive(suggestions, item, pendingOrdered, targetLevel,
                        "No recent consumption history - targeting double the reorder threshold as a safe starting point.");
                continue;
            }

            Map<DayOfWeek, BigDecimal> totalByWeekday = new java.util.EnumMap<>(DayOfWeek.class);
            Map<DayOfWeek, Integer> occurrencesByWeekday = new java.util.EnumMap<>(DayOfWeek.class);
            java.util.Set<LocalDate> distinctDatesSeen = new java.util.HashSet<>();
            for (InventoryTransaction t : history) {
                LocalDate d = t.getCreatedAt().toLocalDate();
                DayOfWeek dow = d.getDayOfWeek();
                totalByWeekday.merge(dow, t.getQuantity(), BigDecimal::add);
                if (distinctDatesSeen.add(d)) {
                    occurrencesByWeekday.merge(dow, 1, Integer::sum);
                }
            }
            BigDecimal overallTotal = history.stream().map(InventoryTransaction::getQuantity).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal overallAvgDaily = overallTotal.divide(BigDecimal.valueOf(SEASONAL_HISTORY_DAYS), 4, RoundingMode.HALF_UP);

            BigDecimal projectedDemand = BigDecimal.ZERO;
            for (int d = 1; d <= ASSUMED_LEAD_TIME_DAYS; d++) {
                DayOfWeek targetDow = LocalDate.now().plusDays(d).getDayOfWeek();
                Integer occurrences = occurrencesByWeekday.get(targetDow);
                BigDecimal weekdayAvg = (occurrences == null || occurrences == 0) ? overallAvgDaily
                        : totalByWeekday.get(targetDow).divide(BigDecimal.valueOf(occurrences), 4, RoundingMode.HALF_UP);
                projectedDemand = projectedDemand.add(weekdayAvg);
            }
            BigDecimal targetLevel = projectedDemand.add(item.getReorderThreshold());
            String reason = "Day-of-week-weighted projection over the next " + ASSUMED_LEAD_TIME_DAYS
                    + " day(s), based on " + SEASONAL_HISTORY_DAYS + " days of history; targets projected demand plus the reorder threshold.";
            addSuggestionIfPositive(suggestions, item, pendingOrdered, targetLevel, reason);
        }

        String aiNarrative = tryGenerateAiNarrative(suggestions);
        return new PurchaseOrderDtos.ReplenishmentSuggestionsResponse(suggestions, aiNarrative);
    }

    private void addSuggestionIfPositive(List<PurchaseOrderDtos.ReplenishmentSuggestionDto> suggestions, InventoryItem item,
                                          BigDecimal pendingOrdered, BigDecimal targetLevel, String reason) {
        BigDecimal suggested = targetLevel.subtract(item.getQuantityOnHand()).subtract(pendingOrdered);
        if (suggested.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        suggestions.add(new PurchaseOrderDtos.ReplenishmentSuggestionDto(item.getId(), item.getName(), item.getUnit(),
                item.getQuantityOnHand(), item.getReorderThreshold(), pendingOrdered, suggested.setScale(3, RoundingMode.HALF_UP), reason));
    }

    // ---- Round 14 (F3.1): opt-in automatic draft-PO generation ----

    /**
     * Called by {@code AutoReplenishmentScheduler}. Gated behind {@code
     * Restaurant#isAutoPoFromSuggestionsEnabled()} (default off) AND requires the deployment to
     * have exactly one {@link Branch} - {@link PurchaseOrder#getBranch()} is required (not
     * nullable) and {@link InventoryItem} is not branch-scoped, so a multi-branch chain has no
     * unambiguous single answer for "which branch does this auto-created PO belong to"; rather
     * than guess, this deliberately skips automatic generation entirely for any multi-branch
     * deployment and logs why - a manager there still has the manual "create draft PO from
     * suggestions" action, which asks them to pick the branch explicitly.
     *
     * <p>Further requires each {@link InventoryItem} to have {@link InventoryItem
     * #getPreferredSupplier()} set - grouping by supplier is how one auto-created PO ends up with
     * more than one line, exactly like a manually-built one. Items with no preferred supplier are
     * simply left out (same as an item with no {@code reorderThreshold} is already left out of
     * low-stock alerting) - nothing here ever guesses a supplier.
     *
     * <p>Every PO this creates lands in {@code PurchaseOrderStatus#DRAFT}, exactly like a manually
     * created one - it is NEVER auto-submitted or auto-approved. A manager still reviews, edits if
     * needed, and submits it through the ordinary Purchase Order workflow; a {@code Notification}
     * is raised so this doesn't happen silently. The attributed {@code createdBy} is the
     * deployment's first ADMIN-role user (there is no "system" account in this schema -
     * {@code PurchaseOrder#createdBy} is not nullable) - the notes text always makes clear the PO
     * was auto-generated, not actually created by that person.
     */
    @Transactional
    public void runAutoPoGeneration() {
        Restaurant restaurant = restaurantRepository.findAll().stream().findFirst().orElse(null);
        if (restaurant == null || !restaurant.isAutoPoFromSuggestionsEnabled()) {
            return;
        }
        List<Branch> branches = branchRepository.findAll();
        if (branches.size() != 1) {
            log.info("Auto-PO generation skipped - {} branch(es) exist; ambiguous which branch an auto-created PO "
                    + "would belong to. Use the manual \"create draft PO from suggestions\" action instead.", branches.size());
            return;
        }
        AppUser systemActor = appUserRepository.findAll().stream()
                .filter(u -> u.getRole() != null && "ADMIN".equalsIgnoreCase(u.getRole().getName()))
                .findFirst().orElse(null);
        if (systemActor == null) {
            log.info("Auto-PO generation skipped - no ADMIN-role user exists to attribute the draft PO to.");
            return;
        }

        PurchaseOrderDtos.ReplenishmentSuggestionsResponse response = generateSeasonalSuggestions();
        Map<UUID, List<PurchaseOrderDtos.CreatePurchaseOrderItemRequest>> itemsBySupplier = new LinkedHashMap<>();
        for (PurchaseOrderDtos.ReplenishmentSuggestionDto suggestion : response.suggestions()) {
            InventoryItem item = inventoryItemRepository.findById(suggestion.inventoryItemId()).orElse(null);
            if (item == null || item.getPreferredSupplier() == null) {
                continue;
            }
            BigDecimal unitPrice = item.getCostPerUnit() == null ? BigDecimal.ZERO : item.getCostPerUnit();
            itemsBySupplier.computeIfAbsent(item.getPreferredSupplier().getId(), k -> new ArrayList<>())
                    .add(new PurchaseOrderDtos.CreatePurchaseOrderItemRequest(item.getId(), suggestion.suggestedQuantity(), unitPrice));
        }

        Branch onlyBranch = branches.get(0);
        String notes = "Auto-generated by the seasonality-aware replenishment scheduler on " + LocalDate.now()
                + " - review quantities/prices and submit for approval when ready.";
        for (Map.Entry<UUID, List<PurchaseOrderDtos.CreatePurchaseOrderItemRequest>> entry : itemsBySupplier.entrySet()) {
            try {
                PurchaseOrder po = purchaseOrderService.createPurchaseOrder(onlyBranch.getId(), entry.getKey(), notes,
                        entry.getValue(), systemActor.getId());
                notificationService.create("AUTO_PO_CREATED",
                        "Draft PO " + po.getPoNumber() + " was auto-generated from seasonality-aware replenishment - review before submitting.",
                        po.getId());
            } catch (Exception ex) {
                log.error("Auto-PO generation failed for supplier {}: {}", entry.getKey(), ex.getMessage(), ex);
            }
        }
    }
}
