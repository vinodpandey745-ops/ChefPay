package com.chefpay.javafx.client.dto;

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

    /** Mirrors {@code com.chefpay.server.dashboard.DashboardDtos.AnalyticsDto} - the "Graphical"/
     * "Both" dashboard mode's chart data (Round 12 §5). */
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
