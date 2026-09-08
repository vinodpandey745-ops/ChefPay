package com.chefpay.javafx.client.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Mirrors {@code com.chefpay.server.reports.ReportDtos} - used by the new Reports screen. */
public final class ReportDtos {

    private ReportDtos() {
    }

    public record SalesReportDto(LocalDate from, LocalDate to, BigDecimal totalSales, long orderCount,
                                  long paymentCount, BigDecimal averageOrderValue,
                                  List<PaymentMethodTotalDto> paymentMethodBreakdown, List<TopItemDto> topItems,
                                  List<CategoryTotalDto> categoryBreakdown, List<OrderTypeTotalDto> orderTypeBreakdown,
                                  List<EmployeeTotalDto> employeeBreakdown, List<TipTotalDto> tipBreakdown,
                                  BigDecimal totalTips) {
    }

    public record PaymentMethodTotalDto(String method, BigDecimal total, long paymentCount) {
    }

    public record TopItemDto(String menuItemName, BigDecimal quantitySold, BigDecimal revenue) {
    }

    public record CategoryTotalDto(String categoryName, BigDecimal quantitySold, BigDecimal revenue) {
    }

    public record OrderTypeTotalDto(String orderType, long orderCount, BigDecimal revenue) {
    }

    public record EmployeeTotalDto(String employeeName, BigDecimal totalCollected, long paymentCount) {
    }

    public record TipTotalDto(String waiterName, BigDecimal totalTips, long orderCount) {
    }

    /** Round 14 (F2.5). */
    public record BranchTotalDto(java.util.UUID branchId, String branchName, BigDecimal totalSales, long orderCount) {
    }

    public record ConsolidatedBranchReportDto(LocalDate from, LocalDate to, BigDecimal totalSales, List<BranchTotalDto> branches) {
    }
}
