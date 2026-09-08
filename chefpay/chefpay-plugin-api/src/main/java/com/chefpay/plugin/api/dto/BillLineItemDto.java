package com.chefpay.plugin.api.dto;

import java.math.BigDecimal;

public record BillLineItemDto(
        String itemName,
        BigDecimal quantity,
        String unit,
        BigDecimal unitPrice,
        BigDecimal lineTotal
) {
}
