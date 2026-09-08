package com.chefpay.javafx.client.dto;

import java.time.LocalDateTime;
import java.util.UUID;

/** Mirrors {@code com.chefpay.server.alerts.AlertDtos} - Round 14 (F4.2). */
public final class AlertDtos {

    private AlertDtos() {
    }

    public record NotificationLogDto(UUID id, UUID anomalyId, String category, String channel, String recipient,
                                      String subject, String message, String status, String errorMessage,
                                      boolean escalation, LocalDateTime attemptedAt) {
    }
}
