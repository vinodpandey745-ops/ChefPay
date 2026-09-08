package com.chefpay.server.reports;

import com.chefpay.server.auth.AuthenticatedPrincipal;
import com.chefpay.server.branch.BranchAccessService;
import com.chefpay.server.common.ApiResponse;
import com.chefpay.server.subscription.RequiresFeature;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

/**
 * Bistrodesk Phase 5: both endpoints now accept an optional {@code branchId} query param,
 * access-checked and resolved to a filter via {@link BranchAccessService#resolveBranchFilter} -
 * see {@link ReportService}'s own javadoc for what changes underneath. Before this phase every
 * caller with {@code REPORT_VIEW} saw the WHOLE restaurant's sales here regardless of their own
 * branch assignment - the same isolation gap Phase 1/2 already closed for every other module, just
 * never wired into Reports.
 */
@RestController
@RequestMapping("/api/reports")
@RequiredArgsConstructor
public class ReportController {

    private final ReportService reportService;
    private final BranchAccessService branchAccessService;

    /** Follow-up requirement ("Reports - Date Column": "the correct branch/local time zone is
     * used"): {@code ReportsPage.tsx}'s quick-range buttons used to compute "today"/"yesterday"/
     * "this month" from the BROWSER's own local clock ({@code new Date()}), then send explicit
     * {@code from}/{@code to} dates - meaning {@link #sales}/{@link #branches}'s own branch-aware
     * {@code today()} fallback above never actually ran for this page, since it only applies when
     * {@code from}/{@code to} are omitted entirely. This endpoint gives the frontend the correct
     * branch-aware "today" to anchor its quick-range math on instead, closing that gap without
     * requiring the browser to know anything about IANA timezones itself. */
    @GetMapping("/today")
    @PreAuthorize("hasAuthority('REPORT_VIEW')")
    public ApiResponse<ReportDtos.TodayDto> today(@RequestParam(required = false) UUID branchId) {
        // Read-only "what date is it for this branch" - deliberately does NOT resolve/validate
        // branchId through BranchAccessService the way the two data-returning endpoints below do:
        // this leaks nothing (a date, not this branch's data), and requiring a passing access
        // check here would make the common "no branchId yet, still choosing" case fail before the
        // caller has even picked a branch to filter by.
        return ApiResponse.ok(new ReportDtos.TodayDto(reportService.today(branchId)));
    }

    @GetMapping("/sales")
    @PreAuthorize("hasAuthority('REPORT_VIEW')")
    public ApiResponse<ReportDtos.SalesReportDto> sales(@RequestParam(required = false) LocalDate from,
                                                          @RequestParam(required = false) LocalDate to,
                                                          @RequestParam(required = false) UUID branchId,
                                                          @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        // Bug #11: "today" means the restaurant's configured business day, not the server's own
        // system date - see ReportService#today's javadoc. Follow-up requirement ("Reports - Date
        // Column"): when a specific branchId is on the request, today() prefers THAT branch's own
        // timezone (ReportService#today(UUID)) - unchanged when branchId is omitted.
        LocalDate effectiveFrom = from != null ? from : reportService.today(branchId);
        LocalDate effectiveTo = to != null ? to : reportService.today(branchId);
        Set<UUID> branchIds = branchAccessService.resolveBranchFilter(principal, branchId);
        return ApiResponse.ok(reportService.getSalesReport(effectiveFrom, effectiveTo, branchIds));
    }

    /** Round 14 (F2.5) - Multi-Branch Consolidated Reporting. Bistrodesk Phase 4 (requirement #24):
     * gated on {@code ADVANCED_REPORTS} - unlike the basic {@link #sales} report every plan
     * includes, a consolidated view across branches is the kind of add-on capability a plan tier
     * can reasonably withhold. See {@link RequiresFeature}'s javadoc. {@code branchId} is rarely
     * useful here (the whole point of this endpoint is a multi-branch breakdown) but is accepted
     * for consistency with {@link #sales} and to let a restricted multi-branch caller narrow the
     * breakdown to fewer than all of their own branches if they want to. */
    @GetMapping("/branches")
    @PreAuthorize("hasAuthority('REPORT_VIEW')")
    @RequiresFeature("ADVANCED_REPORTS")
    public ApiResponse<ReportDtos.ConsolidatedBranchReportDto> branches(@RequestParam(required = false) LocalDate from,
                                                                        @RequestParam(required = false) LocalDate to,
                                                                        @RequestParam(required = false) UUID branchId,
                                                                        @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        LocalDate effectiveFrom = from != null ? from : reportService.today(branchId);
        LocalDate effectiveTo = to != null ? to : reportService.today(branchId);
        Set<UUID> branchIds = branchAccessService.resolveBranchFilter(principal, branchId);
        return ApiResponse.ok(reportService.getConsolidatedBranchReport(effectiveFrom, effectiveTo, branchIds));
    }
}
