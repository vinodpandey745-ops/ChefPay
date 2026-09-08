package com.chefpay.server.ai;

import com.chefpay.core.domain.Branch;
import com.chefpay.core.domain.Restaurant;
import com.chefpay.core.domain.Subscription;
import com.chefpay.core.repository.BranchRepository;
import com.chefpay.core.repository.SubscriptionRepository;
import com.chefpay.core.service.EntitlementService;
import com.chefpay.server.common.ApiException;
import com.chefpay.server.notifications.NotificationService;
import com.chefpay.server.reports.ReportDtos;
import com.chefpay.server.reports.ReportService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

/**
 * Feature F - Nightly AI summary. Shared by {@link AiNightlySummaryScheduler}'s cron job and the
 * "Generate Now" manual endpoint ({@code AiNightlySummaryController}) so a pilot restaurant doesn't
 * have to wait until the scheduled hour to see what this feature does the first time they turn it on.
 */
@Service
@RequiredArgsConstructor
public class AiNightlySummaryService {

    private static final String SYSTEM_PROMPT = """
            You write a short, friendly end-of-day recap for a restaurant owner/manager - 3 to 5
            sentences, based on the day's sales report JSON you are given (total sales, order count,
            top-selling items, payment method breakdown). Mention the total sales figure and order
            count, call out the top-selling item(s) by name, and add one brief observation or word of
            encouragement. Do not invent any figure that isn't in the JSON you were given.
            """;

    private final AiService aiService;
    private final ReportService reportService;
    private final NotificationService notificationService;
    private final ObjectMapper objectMapper;
    private final BranchRepository branchRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final EntitlementService entitlementService;

    /** Generates today's recap and posts it to the Notification inbox; returns the generated text so
     * a manual trigger can also show it immediately rather than only through the inbox.
     *
     * <p>Bistrodesk Phase 4 (requirement #24): the manual "Generate Now" path already goes through
     * {@code AiNightlySummaryController}'s {@code @RequiresFeature("AI_FEATURES")} gate, but the
     * 11:30 PM cron trigger ({@link AiNightlySummaryScheduler}) calls this method directly -
     * outside any controller, so that AOP gate can never see it. {@link #assertAiFeatureEntitled}
     * is this method's own copy of the same check, run first, so an install whose plan doesn't
     * include (or whose subscription no longer includes, e.g. expired/downgraded) {@code
     * AI_FEATURES} never has AI spend triggered by the unattended nightly job either. */
    public String generateAndNotify() {
        Restaurant restaurant = aiService.loadRestaurant();
        aiService.assertEnabled(restaurant, restaurant.isAiNightlySummaryEnabled(), "Nightly AI summary");
        assertAiFeatureEntitled();

        LocalDate today = LocalDate.now();
        // Bistrodesk Phase 5: null = unrestricted/every branch - deliberate, see this method's own
        // "restaurant-wide, not scoped to any one branch" reasoning a few lines below on
        // assertAiFeatureEntitled, and ReportService#getSalesReport's javadoc for what null means.
        ReportDtos.SalesReportDto report = reportService.getSalesReport(today, today, null);
        String json;
        try {
            json = objectMapper.writeValueAsString(report);
        } catch (Exception ex) {
            json = "{}";
        }

        String summary = aiService.chatText(restaurant, SYSTEM_PROMPT, "Today's (" + today + ") sales report JSON:\n" + json);
        notificationService.create("AI_DAILY_SUMMARY", summary, null);
        return summary;
    }

    /** This report is restaurant-wide (one shared nightly recap, not scoped to any one branch - see
     * {@link #generateAndNotify}'s single {@code reportService.getSalesReport} call), so unlike a
     * per-request {@code RequiresFeatureAspect} check there is no one obvious branch to resolve.
     * Consulted the same way {@code entitlement} questions with no natural branch already resolve
     * elsewhere in this codebase when genuinely restaurant-wide (e.g. {@code
     * BranchController#create}'s MULTI_BRANCH check): if ANY of the restaurant's branches currently
     * has an active subscription that includes {@code AI_FEATURES}, the restaurant as a whole is
     * entitled to this shared feature. Throws the same {@code ApiException} shape {@code
     * AiService#assertEnabled} already throws, which {@link AiNightlySummaryScheduler} already
     * catches and quietly skips - a plan without AI is indistinguishable, from the scheduler's
     * point of view, from AI simply being turned off in Settings. */
    private void assertAiFeatureEntitled() {
        List<Branch> branches = branchRepository.findAll();
        boolean entitled = branches.stream()
                .map(b -> subscriptionRepository.findByBranchId(b.getId()))
                .flatMap(java.util.Optional::stream)
                .anyMatch((Subscription s) -> entitlementService.isFeatureEnabled(s, "AI_FEATURES"));
        if (!entitled) {
            throw ApiException.badRequest("AI_FEATURE_DISABLED",
                    "AI Features is not included in this restaurant's current plan.");
        }
    }
}
