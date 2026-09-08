package com.chefpay.server.kot;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public final class KotDtos {

    private KotDtos() {
    }

    public record KotTicketItemDto(UUID orderItemId, String menuItemName, BigDecimal quantity, String status, String specialInstructions) {
    }

    public record KotTicketDto(long kotNumber, UUID orderId, String orderNumber, String tableName, String orderType, LocalDateTime sentAt, List<KotTicketItemDto> items) {
    }
}
