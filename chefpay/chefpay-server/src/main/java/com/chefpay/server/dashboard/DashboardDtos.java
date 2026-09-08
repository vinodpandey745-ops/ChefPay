package com.chefpay.server.dashboard;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public final class DashboardDtos {

    private DashboardDtos() {
    }

    public record SummaryDto(BigDecimal todaySalesTotal, long todayOrderCount, long todayPaymentCount,
                              long openOrderCount, long occupiedTableCount, long totalTableCount,
                              long lowStockItemCount) {
    }

    /** Round 12 §5 - "Graphical" dashboard mode's data source. Every figure here is computed live
     * from the same repositories {@link SummaryDto} already uses (no separate reporting/analytics
     * store) - see {@code DashboardService#getAnalytics}'s javadoc for exactly what date window
     * and attribution rule each list uses. */
    public record AnalyticsDto(
            List<DailySalesPointDto> salesTrend,
            List<CategorySalesDto> categoryBreakdown,
            List<TopItemDto> topItems,
            List<PaymentMethodSalesDto> paymentMethods,
            List<BranchSalesDto> branchSales,
            BigDecimal discountTotalToday,
            BigDecimal taxTotalToday,
            BigDecimal averageOrderValueToday,
            BigDecimal totalInventoryValue
    ) {
    }

    public record DailySalesPointDto(LocalDate date, BigDecimal total) {
    }

    public record CategorySalesDto(String categoryName, BigDecimal total) {
    }

    public record TopItemDto(String itemName, BigDecimal quantitySold, BigDecimal revenue) {
    }

    public record PaymentMethodSalesDto(String method, BigDecimal total, long count) {
    }

    public record BranchSalesDto(String branchName, BigDecimal total) {
    }
}
