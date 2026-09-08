package com.chefpay.plugin.api.dto;

public record PaymentResultDto(
        boolean success,
        String transactionReference,
        String message
) {
}
