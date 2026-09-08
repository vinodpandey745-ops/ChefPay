package com.chefpay.server.ai;

import com.chefpay.core.domain.AuditLog;
import com.chefpay.core.domain.InventoryItem;
import com.chefpay.core.domain.InventoryTransaction;
import com.chefpay.core.domain.InventoryTransactionType;
import com.chefpay.core.domain.Restaurant;
import com.chefpay.core.repository.AppUserRepository;
import com.chefpay.core.repository.AuditLogRepository;
import com.chefpay.core.repository.InventoryTransactionRepository;
import com.chefpay.server.common.ApiResponse;
import com.chefpay.server.inventory.InventoryService;
import com.chefpay.server.subscription.RequiresFeature;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Two related back-office AI helpers grouped in one controller (Round 10): Feature C (smart
 * reorder drafts, {@link #reorderDraft}) and Feature D (audit anomaly flagging, {@link #anomalyScan}).
 * Both are read-only "AI drafts a summary of what's already in the database" features - neither
 * writes anything, they only call out to the configured AI provider and hand back text for a human
 * to read, edit and act on (send to a supplier, look into a flagged pattern).
 *
 * <p>Bistrodesk Phase 4 (requirement #24): requires the {@code AI_FEATURES} plan feature - see
 * {@link RequiresFeature}'s javadoc.
 */
@RestController
@RequestMapping("/api/ai/ops")
@RequiredArgsConstructor
@RequiresFeature("AI_FEATURES")
public class AiOpsController {

    private static final DateTimeFormatter TS_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private static final String REORDER_SYSTEM_PROMPT = """
            You help a small restaurant's owner/manager draft a supplier reorder message. You will be
            given a list of currently low-on-stock ingredients/supplies, each with its unit, quantity
            on hand, reorder threshold, cost per unit, and recent stock-deduction history (date and
            quantity deducted) where available. Draft ONE short, polite, ready-to-send message (suitable
            for WhatsApp or SMS to a supplier) that lists each item with a suggested reorder quantity -
            base the suggested quantity on the recent consumption pace when history is given (aim for
            roughly 1-2 weeks of supply at that pace), or a sensible round number above the threshold
            when no history is available. Keep it practical and concise - a short intro line, then the
            item list, no more than a few sentences of extra commentary.
            """;

    private static final String ANOMALY_SYSTEM_PROMPT = """
            You review a restaurant's audit log for anomalies worth a manager's attention - things like
            an unusually high number of voids/cancellations/discounts/complimentary items from one
            employee, discounts or comps clustered at odd hours, or repeated identical actions in a
            short window that could indicate a process problem or misuse. You will be given a JSON list
            of audit entries (user, action, entity type, reason, timestamp) for a date range. Respond
            with a short plain-text report: either "No unusual patterns found in this period." or a
            short bulleted list (using "- " prefixes, no markdown headers) of specific flagged patterns,
            each naming the user/action/timeframe involved and why it stood out. Do not flag routine,
            evenly-distributed activity - only genuinely unusual concentrations or patterns.
            """;

    private final AiService aiService;
    private final InventoryService inventoryService;
    private final InventoryTransactionRepository transactionRepository;
    private final AuditLogRepository auditLogRepository;
    private final AppUserRepository appUserRepository;

    @PostMapping("/reorder-draft")
    @PreAuthorize("hasAuthority('INVENTORY_VIEW') and hasAuthority('AI_USE')")
    public ApiResponse<AiOpsDtos.ReorderDraftResponse> reorderDraft() {
        Restaurant restaurant = aiService.loadRestaurant();
        aiService.assertEnabled(restaurant, restaurant.isAiReorderDraftsEnabled(), "Smart reorder drafts");

        // Bistrodesk Phase 2: InventoryService#listLowStock now takes an accessible-branch-ids
        // filter - null keeps this endpoint's existing whole-install behavior unchanged (branch-
        // scoping AI ops itself is a later phase's concern, not this one's).
        List<InventoryItem> lowStock = inventoryService.listLowStock(null);
        if (lowStock.isEmpty()) {
            return ApiResponse.ok(new AiOpsDtos.ReorderDraftResponse(
                    "Nothing is currently at or below its reorder threshold - no draft needed.", 0));
        }

        StringBuilder items = new StringBuilder();
        for (InventoryItem item : lowStock) {
            items.append("- ").append(item.getName()).append(": ").append(item.getQuantityOnHand().stripTrailingZeros().toPlainString())
                    .append(" ").append(item.getUnit()).append(" on hand, reorder threshold ")
                    .append(item.getReorderThreshold() == null ? "n/a" : item.getReorderThreshold().stripTrailingZeros().toPlainString())
                    .append(", cost/unit ").append(item.getCostPerUnit() == null ? "n/a" : item.getCostPerUnit().toPlainString())
                    .append(". Recent deductions: ");
            List<InventoryTransaction> recent = transactionRepository.findByItemIdOrderByCreatedAtDesc(item.getId());
            int shown = 0;
            for (InventoryTransaction t : recent) {
                if (t.getType() != InventoryTransactionType.DEDUCT && t.getType() != InventoryTransactionType.WASTE) {
                    continue;
                }
                if (shown >= 10) {
                    break;
                }
                items.append(t.getQuantity().stripTrailingZeros().toPlainString()).append(" on ")
                        .append(t.getCreatedAt() == null ? "?" : t.getCreatedAt().format(TS_FORMAT)).append("; ");
                shown++;
            }
            if (shown == 0) {
                items.append("none recorded.");
            }
            items.append("\n");
        }

        String draft = aiService.chatText(restaurant, REORDER_SYSTEM_PROMPT, items.toString());
        return ApiResponse.ok(new AiOpsDtos.ReorderDraftResponse(draft, lowStock.size()));
    }

    @PostMapping("/anomaly-scan")
    @PreAuthorize("hasAuthority('AUDIT_VIEW') and hasAuthority('AI_USE')")
    public ApiResponse<AiOpsDtos.AnomalyScanResponse> anomalyScan(@Valid @RequestBody AiOpsDtos.AnomalyScanRequest request) {
        Restaurant restaurant = aiService.loadRestaurant();
        aiService.assertEnabled(restaurant, restaurant.isAiAnomalyFlaggingEnabled(), "Audit anomaly flagging");

        LocalDate to = request.to() != null ? request.to() : LocalDate.now();
        LocalDate from = request.from() != null ? request.from() : to.minusDays(7);
        LocalDateTime fromTs = from.atStartOfDay();
        LocalDateTime toTs = to.plusDays(1).atStartOfDay().minus(1, java.time.temporal.ChronoUnit.SECONDS);

        List<AuditLog> entries = auditLogRepository.findByTimestampBetweenOrderByTimestampDesc(fromTs, toTs).stream()
                .filter(this::isAnomalyRelevant)
                .toList();

        if (entries.isEmpty()) {
            return ApiResponse.ok(new AiOpsDtos.AnomalyScanResponse(
                    "No void/discount/complimentary/cancellation activity found in this period to scan.", 0, from, to));
        }

        Map<UUID, String> nameCache = new HashMap<>();
        StringBuilder prompt = new StringBuilder("Audit entries from ").append(from).append(" to ").append(to).append(":\n");
        for (AuditLog entry : entries) {
            String userName = entry.getUserId() == null ? "system"
                    : nameCache.computeIfAbsent(entry.getUserId(), this::resolveUserName);
            prompt.append("- ").append(entry.getTimestamp().format(TS_FORMAT)).append(" | ").append(userName)
                    .append(" | ").append(entry.getAction()).append(" ").append(entry.getEntityType())
                    .append(entry.getReason() == null || entry.getReason().isBlank() ? "" : " | reason: " + entry.getReason())
                    .append("\n");
        }

        String summary = aiService.chatText(restaurant, ANOMALY_SYSTEM_PROMPT, prompt.toString());
        return ApiResponse.ok(new AiOpsDtos.AnomalyScanResponse(summary, entries.size(), from, to));
    }

    private boolean isAnomalyRelevant(AuditLog entry) {
        String action = entry.getAction() == null ? "" : entry.getAction().toUpperCase();
        return action.contains("VOID") || action.contains("DISCOUNT") || action.contains("CANCEL")
                || action.contains("COMPLIMENTARY") || action.contains("WASTE") || action.contains("DELETE")
                || action.contains("REFUND");
    }

    private String resolveUserName(UUID userId) {
        return appUserRepository.findById(userId).map(com.chefpay.core.domain.AppUser::getDisplayName).orElse("unknown user");
    }
}
