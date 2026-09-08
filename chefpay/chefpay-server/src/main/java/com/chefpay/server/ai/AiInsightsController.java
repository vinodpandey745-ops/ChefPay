package com.chefpay.server.ai;

import com.chefpay.core.domain.Restaurant;
import com.chefpay.server.auth.AuthenticatedPrincipal;
import com.chefpay.server.branch.BranchAccessService;
import com.chefpay.server.common.ApiResponse;
import com.chefpay.server.reports.ReportDtos;
import com.chefpay.server.reports.ReportService;
import com.chefpay.server.subscription.RequiresFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

/**
 * Feature B - "Ask Your Data": a natural-language chat over the same sales/report figures
 * {@code ReportsView} already shows in tables and charts, for the owner who'd rather type "what
 * were my top 3 sellers last week" than dig through a report screen. Deliberately read-only and
 * scoped to a single request/response (no multi-turn conversation memory server-side) - every call
 * re-fetches {@link ReportService#getSalesReport} fresh and hands the AI exactly that JSON as its
 * only source of truth, with an explicit instruction not to answer from anything else, so the
 * "chat" can never invent figures the restaurant's own data doesn't actually show.
 *
 * <p>Bistrodesk Phase 4 (requirement #24): requires the {@code AI_FEATURES} plan feature - see
 * {@link RequiresFeature}'s javadoc.
 *
 * <p>Bistrodesk Phase 5 (confirmed gap, found while wiring branch-scoped reporting): this endpoint
 * used to fetch the WHOLE restaurant's sales report for every caller regardless of branch
 * assignment - a Manager restricted to one branch could ask this chat about, and receive figures
 * for, every OTHER branch too. Now defaults to {@link BranchAccessService#resolveBranchFilter} the
 * same way every other report/dashboard endpoint does; an unrestricted caller (or a single-branch
 * install) is unaffected.
 */
@RestController
@RequestMapping("/api/ai/insights")
@RequiredArgsConstructor
@Slf4j
@RequiresFeature("AI_FEATURES")
public class AiInsightsController {

    private static final String SYSTEM_PROMPT = """
            You are a restaurant analytics assistant. You will be given one JSON object with this
            restaurant's sales report for a specific date range, followed by a question from the owner
            or manager. Answer ONLY using the numbers in that JSON - never invent a figure that isn't
            derivable from it, and say so plainly if the data provided can't answer the question. Keep
            answers concise (a few sentences, or a short list) and use the restaurant's own currency
            figures as given, without re-formatting them. Do not repeat the raw JSON back verbatim.
            """;

    private final AiService aiService;
    private final ReportService reportService;
    private final ObjectMapper objectMapper;
    private final BranchAccessService branchAccessService;

    @PostMapping("/chat")
    @PreAuthorize("hasAuthority('REPORT_VIEW') and hasAuthority('AI_USE')")
    public ApiResponse<AiInsightsDtos.ChatResponse> chat(@Valid @RequestBody AiInsightsDtos.ChatRequest request,
                                                          @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        Restaurant restaurant = aiService.loadRestaurant();
        aiService.assertEnabled(restaurant, restaurant.isAiInsightsChatEnabled(), "\"Ask Your Data\"");

        LocalDate to = request.to() != null ? request.to() : LocalDate.now();
        LocalDate from = request.from() != null ? request.from() : to.minusDays(30);
        Set<UUID> branchIds = branchAccessService.resolveBranchFilter(principal, null);
        ReportDtos.SalesReportDto report = reportService.getSalesReport(from, to, branchIds);

        String reportJson = toJsonSafely(report);
        String userPrompt = "Sales report JSON for " + from + " to " + to + ":\n" + reportJson
                + "\n\nQuestion: " + request.question();
        String answer = aiService.chatText(restaurant, SYSTEM_PROMPT, userPrompt);
        return ApiResponse.ok(new AiInsightsDtos.ChatResponse(answer, from, to));
    }

    private String toJsonSafely(ReportDtos.SalesReportDto report) {
        try {
            return objectMapper.writeValueAsString(report);
        } catch (Exception ex) {
            // Round 27 (pre-deployment audit) fix: this previously swallowed the failure with no
            // logging at all - a serialization bug here would silently hand the AI an empty "{}"
            // report (a wrong-but-plausible-looking answer to the user's question) with zero trace
            // anywhere that anything went wrong. Logging it doesn't change the fallback behavior
            // (still returns "{}", since the chat call must not hard-fail over a report-formatting
            // problem) but makes a real bug here diagnosable instead of invisible.
            log.warn("ChefPay: failed to serialize sales report JSON for the AI chat prompt - "
                    + "falling back to an empty report body.", ex);
            return "{}";
        }
    }
}
