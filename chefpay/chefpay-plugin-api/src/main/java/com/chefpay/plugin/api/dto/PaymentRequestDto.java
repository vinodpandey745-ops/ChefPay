package com.chefpay.plugin.api.dto;

import java.math.BigDecimal;

public record PaymentRequestDto(
        String billNumber,
        BigDecimal amount,
        String currency,
        String method
) {
}
