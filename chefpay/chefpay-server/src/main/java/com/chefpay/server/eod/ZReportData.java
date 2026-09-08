package com.chefpay.server.eod;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Everything the Z-Report (text and PDF renderings) needs - assembled once in {@code EodService
 * #finalize} and handed to both {@code EodService#buildZReportText} and {@link ZReportPdfBuilder}
 * so the two renderings can never disagree on the underlying numbers.
 */
public record ZReportData(
        String restaurantName,
        LocalDate businessDate,
        BigDecimal grossSales,
        BigDecimal discountTotal,
        BigDecimal taxTotal,
        BigDecimal serviceChargeTotal,
        BigDecimal tipTotal,
        BigDecimal netTotal,
        List<TenderLine> tenderBreakdown,
        int ordersBilled,
        BigDecimal openingFloat,
        BigDecimal expectedCash,
        BigDecimal physicalCash,
        BigDecimal cashVariance,
        String cashCountStatus,
        int anomaliesTotal,
        int anomaliesHighCritical,
        int anomaliesUnresolved,
        List<String> anomalySummaryLines,
        boolean finalizedWithOverride,
        String finalizeOverrideReason,
        /** Round 14 (F2.2) - "EOD-summary inclusion" for low-stock items, reusing {@code
         * InventoryService#listLowStock} exactly as the Alerts inbox already does; empty when
         * nothing is currently low. */
        List<String> lowStockSummaryLines
) {
    public record TenderLine(String method, BigDecimal amount, long count) {
    }
}
