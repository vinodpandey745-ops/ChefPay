package com.chefpay.server.dashboard;

import com.chefpay.server.auth.AuthenticatedPrincipal;
import com.chefpay.server.branch.BranchAccessService;
import com.chefpay.server.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;
import java.util.UUID;

/**
 * Bistrodesk Phase 5: both endpoints now accept an optional {@code branchId} query param and
 * resolve it via {@link BranchAccessService#resolveBranchFilter} - see {@link DashboardService}'s
 * own javadoc for what changes underneath. A caller with no specific branches assigned (or holding
 * {@code VIEW_ALL_BRANCHES}) omitting {@code branchId} sees the whole restaurant, exactly as
 * before Phase 5; a branch-restricted caller omitting it now sees only their own branch(es) instead
 * of the whole restaurant, closing the isolation gap Phase 1/2 already closed everywhere else.
 */
@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboardService;
    private final BranchAccessService branchAccessService;

    @GetMapping("/summary")
    @PreAuthorize("hasAuthority('DASHBOARD_VIEW')")
    public ApiResponse<DashboardDtos.SummaryDto> summary(@RequestParam(required = false) UUID branchId,
                                                           @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        Set<UUID> branchIds = branchAccessService.resolveBranchFilter(principal, branchId);
        return ApiResponse.ok(dashboardService.getSummary(branchIds));
    }

    /** Round 12 §5 - chart data for the "Graphical"/"Both" dashboard mode. Same permission as
     * {@link #summary} - this is a different shape of the same "what's happening right now" data,
     * not a separately-gated capability. */
    @GetMapping("/analytics")
    @PreAuthorize("hasAuthority('DASHBOARD_VIEW')")
    public ApiResponse<DashboardDtos.AnalyticsDto> analytics(@RequestParam(required = false) UUID branchId,
                                                              @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        Set<UUID> branchIds = branchAccessService.resolveBranchFilter(principal, branchId);
        return ApiResponse.ok(dashboardService.getAnalytics(branchIds));
    }
}
