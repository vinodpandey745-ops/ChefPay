package com.chefpay.javafx.client.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** Mirrors {@code com.chefpay.server.kot.KotDtos} (Round 8 KOT Listing screen). */
public final class KotDtos {

    private KotDtos() {
    }

    public record KotTicketItemDto(UUID orderItemId, String menuItemName, BigDecimal quantity, String status, String specialInstructions) {
    }

    public record KotTicketDto(long kotNumber, UUID orderId, String orderNumber, String tableName, String orderType, LocalDateTime sentAt, List<KotTicketItemDto> items) {
    }
}
