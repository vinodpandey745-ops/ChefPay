package com.chefpay.server.pricing;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public final class PriceSuggestionDtos {

    private PriceSuggestionDtos() {
    }

    public record SuggestionDto(UUID id, UUID menuItemId, String menuItemName, BigDecimal currentPrice,
                                 BigDecimal currentRecipeCost, BigDecimal currentMarginPercent,
                                 BigDecimal suggestedPrice, BigDecimal projectedMarginPercent, String reason,
                                 String status, LocalDateTime detectedAt, String decidedByName,
                                 LocalDateTime decidedAt, String decisionNote, long version) {
    }

    public record DecideRequest(String note) {
    }
}
