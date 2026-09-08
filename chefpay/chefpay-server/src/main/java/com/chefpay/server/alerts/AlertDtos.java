package com.chefpay.server.alerts;

import java.time.LocalDateTime;
import java.util.UUID;

public final class AlertDtos {

    private AlertDtos() {
    }

    public record NotificationLogDto(UUID id, UUID anomalyId, String category, String channel, String recipient,
                                      String subject, String message, String status, String errorMessage,
                                      boolean escalation, LocalDateTime attemptedAt) {
    }
}
