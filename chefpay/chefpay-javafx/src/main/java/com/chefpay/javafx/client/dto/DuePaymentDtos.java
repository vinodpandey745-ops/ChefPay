package com.chefpay.javafx.client.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/** Mirrors {@code com.chefpay.server.duepayments.DuePaymentDtos} (Round 8 Due Payment Management screen). */
public final class DuePaymentDtos {

    private DuePaymentDtos() {
    }

    public record DuePaymentDto(UUID orderId, String orderNumber, String tableName, String customerName,
                                 String customerPhone, BigDecimal totalAmount, String paymentStatus,
                                 LocalDateTime billedAt) {
    }
}
