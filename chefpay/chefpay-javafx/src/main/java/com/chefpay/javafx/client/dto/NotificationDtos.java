package com.chefpay.javafx.client.dto;

import java.time.LocalDateTime;
import java.util.UUID;

/** Mirrors {@code com.chefpay.server.notifications.NotificationDtos} (Round 8 Alerts/Notification inbox screen). */
public final class NotificationDtos {

    private NotificationDtos() {
    }

    public record NotificationDto(UUID id, String category, String message, UUID referenceId, boolean read, LocalDateTime createdAt) {
    }
}
