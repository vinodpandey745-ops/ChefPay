package com.chefpay.server.duepayments;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public final class DuePaymentDtos {

    private DuePaymentDtos() {
    }

    public record DuePaymentDto(UUID orderId, String orderNumber, String tableName, String customerName,
                                 String customerPhone, BigDecimal totalAmount, String paymentStatus,
                                 LocalDateTime billedAt) {
    }
}
