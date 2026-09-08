package com.chefpay.plugin.api.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record BillDto(
        String billNumber,
        String tableName,
        List<BillLineItemDto> lineItems,
        BigDecimal subtotal,
        BigDecimal discountAmount,
        BigDecimal tipAmount,
        List<TaxLineDto> taxLines,
        BigDecimal grandTotal,
        String paymentMethod,
        String clerkName,
        LocalDateTime closedAt
) {
    public record TaxLineDto(String label, BigDecimal ratePercent, BigDecimal amount) {
    }
}
