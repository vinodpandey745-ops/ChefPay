package com.chefpay.server.reports;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public final class ReportDtos {

    private ReportDtos() {
    }

    public record SalesReportDto(
            LocalDate from,
            LocalDate to,
            BigDecimal totalSales,
            long orderCount,
            long paymentCount,
            BigDecimal averageOrderValue,
            List<PaymentMethodTotalDto> paymentMethodBreakdown,
            List<TopItemDto> topItems,
            List<CategoryTotalDto> categoryBreakdown,
            List<OrderTypeTotalDto> orderTypeBreakdown,
            List<EmployeeTotalDto> employeeBreakdown,
            List<TipTotalDto> tipBreakdown,
            BigDecimal totalTips
    ) {
    }

    /** Follow-up requirement ("Reports - Date Column": "the correct branch/local time zone is
     * used"): backs {@code GET /reports/today}, letting {@code ReportsPage.tsx}'s quick-range
     * buttons ("Today"/"Yesterday"/"Last 7 days"/"This Month") anchor on the SERVER's branch-aware
     * notion of today ({@code ReportService#today(UUID)}) instead of the browser's own local clock/
     * timezone - the confirmed gap this requirement was written for (a POS terminal or an admin's
     * browser can be in a different timezone, or simply have a wrong system clock, than the branch
     * it's reporting on). */
    public record TodayDto(LocalDate today) {
    }

    public record PaymentMethodTotalDto(String method, BigDecimal total, long paymentCount) {
    }

    public record TopItemDto(String menuItemName, BigDecimal quantitySold, BigDecimal revenue) {
    }

    /** Revenue grouped by {@code MenuItem.category} - answers "what categories are selling", the
     * natural companion to the existing per-item Top-Selling Items table. */
    public record CategoryTotalDto(String categoryName, BigDecimal quantitySold, BigDecimal revenue) {
    }

    /** Revenue grouped by {@code Order.orderType} (DINE_IN/TAKEAWAY/DELIVERY/etc.) - lets a manager
     * see how much of the day's sales came from each channel. */
    public record OrderTypeTotalDto(String orderType, long orderCount, BigDecimal revenue) {
    }

    /** Sales attributed to whoever actually processed the payment ({@code Payment.receivedBy}),
     * not who served the order - a split-bill order can have payments processed by more than one
     * cashier, so this is the only unambiguous "who collected this money" attribution. Distinct
     * from {@link TipTotalDto}, which is intentionally attributed differently (see that record's
     * javadoc). */
    public record EmployeeTotalDto(String employeeName, BigDecimal totalCollected, long paymentCount) {
    }

    /** Tips grouped by {@code Order.waiter} - the person who served the table, not whoever happened
     * to process the payment. Tips are a courtesy for service, so this is the attribution that
     * actually makes sense for a "Tip Summary" report; a combined employee+tip report would be
     * misleading whenever the collecting cashier and the serving waiter differ (e.g. a busy floor
     * where a cashier settles bills for several waiters' tables). */
    public record TipTotalDto(String waiterName, BigDecimal totalTips, long orderCount) {
    }

    /** Round 14 (F2.5) - one branch's totals within a {@link ConsolidatedBranchReportDto}.
     * {@code branchId} is null for the {@code "Unassigned (non-table orders)"} bucket - see that
     * DTO's javadoc for why delivery/takeaway/online orders can't be attributed to a real branch
     * today. */
    public record BranchTotalDto(java.util.UUID branchId, String branchName, BigDecimal totalSales, long orderCount) {
    }

    /**
     * Round 14 (F2.5) - "Multi-Branch Consolidated Reporting." Deliberately a SEPARATE report from
     * {@link SalesReportDto} rather than an added field on it. Every order created since Bistrodesk
     * Phase 2 carries a direct {@code Order.branch} column (dine-in or not - see {@code
     * Order#getEffectiveBranch()}'s javadoc), but a genuinely branch-less order is still possible
     * (one created before that column existed) and can't honestly be counted as any one specific
     * branch's revenue - a real double-counting or misattribution risk in a financial report, unlike
     * {@code OrderRepository#findByStatusNotInAndBranch}'s live table-matrix helper, which
     * deliberately includes every non-table order under EVERY branch (correct for "what's on the
     * floor right now," wrong for money). This consolidated view instead puts any such order into
     * one explicit {@code "Unassigned"} bucket alongside the real per-branch buckets, so every
     * rupee is counted exactly once and nothing is silently dropped or duplicated across branches.
     */
    public record ConsolidatedBranchReportDto(LocalDate from, LocalDate to, BigDecimal totalSales,
                                               List<BranchTotalDto> branches) {
    }
}
